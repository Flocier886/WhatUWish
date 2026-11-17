package com.flocier.infrastructure.adapter.port;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import com.flocier.domain.agent.adapter.port.AgentBrowserLiveView;
import com.flocier.domain.agent.adapter.port.AgentBrowserSession;
import com.flocier.domain.agent.adapter.port.AgentBrowserSessionPort;
import com.flocier.domain.agent.adapter.port.AgentBrowserSessionStatus;
import com.flocier.infrastructure.adapter.repository.AgentBrowserSessionPersistenceService;
import com.flocier.infrastructure.dao.IAiAgentBrowserSessionDao;
import com.flocier.infrastructure.dao.po.AiAgentBrowserSession;
import com.flocier.types.agent.config.AgentBrowserProperties;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Kernel Agent 浏览器会话适配器：按 owner 查找、恢复或创建远端浏览器。
 * 数据库仅保存 owner 与远端会话的稳定映射；每次解析从 Kernel 获取当前 CDP 地址，不写入 MySQL。
 */
@Component
@EnableConfigurationProperties(AgentBrowserProperties.class)
public class AgentBrowserSessionAdapter implements AgentBrowserSessionPort {

    private static final String DEFAULT_BASE_URL = "https://api.onkernel.com";
    private static final int DEFAULT_TIMEOUT_SECONDS = 259200;
    private static final int BROWSER_NAME_HASH_LENGTH = 40;
    /**
     * 进程内按 owner 分片的互斥锁：不占用数据库连接，避免同一用户并发解析出多个浏览器。
     * 分片而非每用户一把锁，保证锁结构有界、不会随用户数增长泄漏。
     */
    private static final int USER_LOCK_STRIPES = 256;

    private final AgentBrowserProperties properties;
    private final IAiAgentBrowserSessionDao sessionDao;
    private final AgentBrowserSessionPersistenceService persistenceService;
    private final AgentBrowserHttpClient httpClient;
    private final ReentrantLock[] userLocks = createUserLocks();

    /**
     * Production constructor. The shared client supplies the application's connection pool and dispatcher.
     */
    @Autowired
    public AgentBrowserSessionAdapter(AgentBrowserProperties properties,
                                      IAiAgentBrowserSessionDao sessionDao,
                                      AgentBrowserSessionPersistenceService persistenceService,
                                       HttpClient sharedClient) {
        this(properties, sessionDao, persistenceService, new JdkAgentBrowserHttpClient(
                sharedClient,
                resolveBaseUrl(properties),
                properties.getApiKey()));
    }

    /**
     * Package-independent test seam. Tests can provide a fake HTTP layer without a real API key or network.
     */
    public AgentBrowserSessionAdapter(AgentBrowserProperties properties,
                                      IAiAgentBrowserSessionDao sessionDao,
                                      AgentBrowserHttpClient httpClient) {
        this(properties, sessionDao, new AgentBrowserSessionPersistenceService(sessionDao), httpClient);
    }

    public AgentBrowserSessionAdapter(AgentBrowserProperties properties,
                                      IAiAgentBrowserSessionDao sessionDao,
                                      AgentBrowserSessionPersistenceService persistenceService,
                                      AgentBrowserHttpClient httpClient) {
        this.properties = Objects.requireNonNull(properties, "AgentBrowserProperties must not be null");
        this.sessionDao = Objects.requireNonNull(sessionDao, "IAiAgentBrowserSessionDao must not be null");
        this.persistenceService = Objects.requireNonNull(persistenceService,
                "AgentBrowserSessionPersistenceService must not be null");
        this.httpClient = Objects.requireNonNull(httpClient, "AgentBrowserHttpClient must not be null");
    }

    private static ReentrantLock[] createUserLocks() {
        ReentrantLock[] locks = new ReentrantLock[USER_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new ReentrantLock();
        }
        return locks;
    }

    /**
     * Small HTTP seam used by the adapter and replaced by a deterministic fake in tests.
     */
    public interface AgentBrowserHttpClient {

        AgentBrowserHttpResponse createBrowser(String name,
                                                int timeoutSeconds,
                                                boolean headless,
                                                String profileName,
                                                boolean profileSaveChanges);

        AgentBrowserHttpResponse getBrowser(String idOrName);

        AgentBrowserHttpResponse deleteBrowser(String idOrName);
    }

    /**
     * HTTP response value used by the fake seam. The body is kept private to the adapter's parsing path.
     */
    public static final class AgentBrowserHttpResponse {

        private final int statusCode;
        private final String body;

        public AgentBrowserHttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public String getBody() {
            return body;
        }
    }

    /** 仅启用开关且有 API Key 时，工具工厂才应暴露 agent_browser。 */
    @Override
    public boolean isConfigured() {
        return properties.isEnabled() && StringUtils.isNotBlank(properties.getApiKey());
    }

    /** 为工具执行解析当前会话；远端已失效时标记重建，CDP 地址只交给调用方。 */
    @Override
    public AgentBrowserSession resolveForUser(String userId) {
        String normalizedUserId = requireUserId(userId);
        requireConfigured();
        try {
            BrowserResolution resolution = withUserLock(normalizedUserId,
                    () -> resolveLocked(normalizedUserId));
            requireCdpEndpoint(resolution.browser());
            return AgentBrowserSession.builder()
                    .userId(normalizedUserId)
                    .agentSessionId(resolution.browser().sessionId())
                    .agentBrowserName(resolution.browser().name())
                    .cdpWsUrl(resolution.browser().cdpWsUrl())
                    .reconstructed(resolution.reconstructed())
                    .build();
        } catch (RuntimeException e) {
            throw sanitizedFailure("resolveForUser", e);
        }
    }

    /** 查询本地映射状态，不为了展示状态而创建远端浏览器。 */
    @Override
    public AgentBrowserSessionStatus statusForUser(String userId) {
        String normalizedUserId = requireUserId(userId);
        try {
            AiAgentBrowserSession row = sessionDao.queryByOwnerKey(normalizedUserId);
            if (row == null) {
                return AgentBrowserSessionStatus.builder()
                        .exists(false)
                        .reconstructed(false)
                        .build();
            }
            return AgentBrowserSessionStatus.builder()
                    .exists(true)
                    .agentSessionId(row.getAgentSessionId())
                    .lastUsedAt(row.getLastUsedAt())
                    .reconstructed(false)
                    .build();
        } catch (RuntimeException e) {
            throw sanitizedFailure("statusForUser", e);
        }
    }

    /** 为工作台打开实时画面；与工具执行复用同一恢复/创建逻辑。 */
    @Override
    public AgentBrowserLiveView ensureLiveView(String userId) {
        String normalizedUserId = requireUserId(userId);
        requireConfigured();
        try {
            BrowserResolution resolution = withUserLock(normalizedUserId,
                    () -> resolveLocked(normalizedUserId));
            requireCdpEndpoint(resolution.browser());
            if (StringUtils.isBlank(resolution.browser().liveViewUrl())) {
                throw new IllegalStateException("Kernel response did not include a live view URL");
            }
            return AgentBrowserLiveView.builder()
                    .browserLiveViewUrl(resolution.browser().liveViewUrl())
                    .agentSessionId(resolution.browser().sessionId())
                    .reconstructed(resolution.reconstructed())
                    .build();
        } catch (RuntimeException e) {
            throw sanitizedFailure("ensureLiveView", e);
        }
    }

    /** 先删除远端实例，再清除本地映射；远端已返回 404 时仍执行本地清理。 */
    @Override
    public void deleteForUser(String userId) {
        String normalizedUserId = requireUserId(userId);
        try {
            withUserLock(normalizedUserId, () -> {
                AiAgentBrowserSession row = sessionDao.queryByOwnerKey(normalizedUserId);
                if (row != null && isConfigured()) {
                    String idOrName = StringUtils.firstNonBlank(
                            row.getAgentSessionId(), row.getAgentBrowserName());
                    if (StringUtils.isNotBlank(idOrName)) {
                        AgentBrowserHttpResponse response = httpClient.deleteBrowser(idOrName);
                        if (response == null) {
                            throw new IllegalStateException("Kernel delete returned no response");
                        }
                        int statusCode = response.getStatusCode();
                        if (!isSuccessful(statusCode) && statusCode != 404) {
                            throw remoteFailure("DELETE browser", statusCode);
                        }
                    }
                }
                // A remote 404 is already the desired remote state; local cleanup remains mandatory.
                persistenceService.deleteMapping(normalizedUserId);
                return null;
            });
        } catch (RuntimeException e) {
            throw sanitizedFailure("deleteForUser", e);
        }
    }

    /** 按会话 ID、稳定浏览器名依次尝试恢复；均失效时才创建替代实例。 */
    private BrowserResolution resolveLocked(String userId) {
        String desiredName = stableBrowserName(userId);
        AiAgentBrowserSession row = sessionDao.queryByOwnerKey(userId);
        if (row == null) {
            return createAndPersist(userId, desiredName, false);
        }

        String sessionId = StringUtils.trimToNull(row.getAgentSessionId());
        if (sessionId != null) {
            BrowserSnapshot browser = getBrowser(sessionId, row.getAgentBrowserName());
            if (browser != null) {
                return touchOrPersistExisting(userId, browser, false);
            }
        }

        // The session id may be gone while the named browser is still recoverable.
        String browserName = StringUtils.defaultIfBlank(row.getAgentBrowserName(), desiredName);
        BrowserSnapshot browser = getBrowser(browserName, desiredName);
        if (browser != null) {
            return touchOrPersistExisting(userId, browser, false);
        }

        // Both references are gone. A new browser is an explicit reconstruction, never a silent resume.
        return createAndPersist(userId, desiredName, true);
    }

    /** 创建后写入稳定映射；同名冲突时按名称找回已存在的实例。 */
    private BrowserResolution createAndPersist(String userId, String browserName, boolean reconstructed) {
        String profileName = StringUtils.trimToNull(properties.getProfileName());
        AgentBrowserHttpResponse response = httpClient.createBrowser(
                browserName,
                resolveBrowserTimeoutSeconds(),
                false,
                profileName,
                properties.isProfileSaveChanges());
        if (response == null) {
            throw new IllegalStateException("Kernel create returned no response");
        }
        if (response.getStatusCode() == 409) {
            // A conflict means the stable name already owns a browser. Reconcile by name instead of creating another one.
            BrowserSnapshot existing = getBrowser(browserName, browserName);
            if (existing == null) {
                throw new IllegalStateException("Agent browser name conflict could not be reconciled");
            }
            return persistAndResolve(userId, existing, reconstructed);
        }
        if (!isSuccessful(response.getStatusCode())) {
            throw remoteFailure("POST browser", response.getStatusCode());
        }
        BrowserSnapshot created = parseBrowser(response, browserName);
        return persistAndResolve(userId, created, reconstructed);
    }

    private BrowserResolution persistAndResolve(String userId,
                                                BrowserSnapshot browser,
                                                boolean reconstructed) {
        BrowserSnapshot normalized = normalizeBrowser(browser);
        persistenceService.upsertMapping(
                userId, normalized.sessionId(), normalized.name(), LocalDateTime.now());
        return new BrowserResolution(normalized, reconstructed);
    }

    private BrowserResolution touchOrPersistExisting(String userId,
                                                      BrowserSnapshot browser,
                                                      boolean reconstructed) {
        BrowserSnapshot normalized = normalizeBrowser(browser);
        // 读-改-写收进持久化服务的短事务；Kernel HTTP 已经在事务外完成。
        persistenceService.touchOrPersistMapping(
                userId, normalized.sessionId(), normalized.name(), LocalDateTime.now());
        return new BrowserResolution(normalized, reconstructed);
    }

    private BrowserSnapshot normalizeBrowser(BrowserSnapshot browser) {
        requireCdpEndpoint(browser);
        String browserName = StringUtils.trimToNull(browser.name());
        if (browserName == null) {
            throw new IllegalStateException("Kernel response did not include a browser name");
        }
        return browser.withName(browserName);
    }

    private BrowserSnapshot getBrowser(String idOrName, String fallbackName) {
        AgentBrowserHttpResponse response = httpClient.getBrowser(idOrName);
        if (response == null) {
            throw new IllegalStateException("Kernel get returned no response");
        }
        if (response.getStatusCode() == 404) {
            return null;
        }
        if (!isSuccessful(response.getStatusCode())) {
            throw remoteFailure("GET browser", response.getStatusCode());
        }
        return parseBrowser(response, fallbackName);
    }

    private BrowserSnapshot parseBrowser(AgentBrowserHttpResponse response, String fallbackName) {
        try {
            JSONObject json = JSON.parseObject(response.getBody());
            if (json == null) {
                throw new IllegalStateException("Kernel response body was empty");
            }
            String sessionId = StringUtils.trimToNull(json.getString("session_id"));
            if (sessionId == null) {
                throw new IllegalStateException("Kernel response did not include a session id");
            }
            String name = StringUtils.defaultIfBlank(json.getString("name"), fallbackName);
            return new BrowserSnapshot(
                    sessionId,
                    StringUtils.trimToNull(name),
                    json.getString("cdp_ws_url"),
                    json.getString("browser_live_view_url"));
        } catch (RuntimeException e) {
            throw sanitizedFailure("parse Agent browser response", e);
        }
    }

    /**
     * 进程内按 owner 分片互斥，不再依赖 MySQL GET_LOCK：锁不再占用数据库连接，也不会被
     * 跨在 Kernel HTTP 调用上。跨实例场景下互斥失效，退化为可能重复创建，由 Kernel 侧
     * 稳定浏览器名（重复创建返回 409 后按名回收）与 owner_key 唯一键兜底。
     */
    private <T> T withUserLock(String userId, Supplier<T> action) {
        ReentrantLock lock = userLocks[Math.floorMod(userId.hashCode(), userLocks.length)];
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("Agent Browser is not configured");
        }
    }

    private String requireUserId(String userId) {
        String normalized = StringUtils.trimToNull(userId);
        if (normalized == null) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        return normalized;
    }

    private void requireCdpEndpoint(BrowserSnapshot browser) {
        if (browser == null || StringUtils.isBlank(browser.cdpWsUrl())) {
            throw new IllegalStateException("Kernel response did not include a CDP endpoint");
        }
    }

    private int resolveBrowserTimeoutSeconds() {
        return properties.getTimeoutSeconds() > 0
                ? properties.getTimeoutSeconds()
                : DEFAULT_TIMEOUT_SECONDS;
    }

    /** 用 owner 哈希生成稳定的远端浏览器名，避免在名称中暴露原始 owner。 */
    private String stableBrowserName(String userId) {
        String prefix = StringUtils.defaultIfBlank(properties.getBrowserNamePrefix(), "rb").trim();
        if (!prefix.matches("[a-zA-Z0-9._-]+")) {
            throw new IllegalStateException("browserNamePrefix contains unsupported characters");
        }
        String name = prefix + "-" + sha256Hex(userId).substring(0, BROWSER_NAME_HASH_LENGTH);
        if (name.length() > 255 || !name.matches("[a-zA-Z0-9._-]{1,255}")) {
            throw new IllegalStateException("Agent browser name is invalid");
        }
        return name;
    }

    private String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte part : digest) {
                hex.append(String.format("%02x", part));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private IllegalStateException remoteFailure(String operation, int statusCode) {
        return new IllegalStateException(operation + " returned HTTP " + statusCode);
    }

    private IllegalStateException sanitizedFailure(String operation, RuntimeException cause) {
        String detail = AgentBrowserCredentialRedactor.redact(cause.getMessage(), properties.getApiKey());
        if (StringUtils.isBlank(detail)) {
            detail = "unknown error";
        }
        return new IllegalStateException(operation + " failed: " + detail);
    }

    private static boolean isSuccessful(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static String resolveBaseUrl(AgentBrowserProperties properties) {
        return StringUtils.defaultIfBlank(properties.getBaseUrl(), DEFAULT_BASE_URL);
    }

    private record BrowserResolution(BrowserSnapshot browser, boolean reconstructed) {
    }

    private record BrowserSnapshot(String sessionId,
                                   String name,
                                   String cdpWsUrl,
                                   String liveViewUrl) {

        private BrowserSnapshot withName(String resolvedName) {
            return new BrowserSnapshot(sessionId, resolvedName, cdpWsUrl, liveViewUrl);
        }
    }

    /** Kernel HTTP 边界：封装鉴权、浏览器 API 请求与异常消息脱敏。 */
    private static final class JdkAgentBrowserHttpClient implements AgentBrowserHttpClient {

        private final HttpClient sharedClient;
        private final String baseUrl;
        private final String apiKey;

        private JdkAgentBrowserHttpClient(HttpClient sharedClient, String baseUrl, String apiKey) {
            this.sharedClient = Objects.requireNonNull(sharedClient, "sharedClient must not be null");
            this.baseUrl = trimTrailingSlash(baseUrl);
            this.apiKey = apiKey == null ? "" : apiKey.trim();
        }

        @Override
        public AgentBrowserHttpResponse createBrowser(String name,
                                                       int timeoutSeconds,
                                                       boolean headless,
                                                       String profileName,
                                                       boolean profileSaveChanges) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", name);
            payload.put("timeout_seconds", timeoutSeconds);
            payload.put("headless", headless);
            if (StringUtils.isNotBlank(profileName)) {
                Map<String, Object> profile = new LinkedHashMap<>();
                profile.put("name", profileName.trim());
                profile.put("save_changes", profileSaveChanges);
                payload.put("profile", profile);
            }
            return execute("POST", "/browsers", JSON.toJSONString(payload));
        }

        @Override
        public AgentBrowserHttpResponse getBrowser(String idOrName) {
            return execute("GET", "/browsers/" + encodePathSegment(idOrName), null);
        }

        @Override
        public AgentBrowserHttpResponse deleteBrowser(String idOrName) {
            return execute("DELETE", "/browsers/" + encodePathSegment(idOrName), null);
        }

        private AgentBrowserHttpResponse execute(String method, String path, String body) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json");
            if (body != null) builder.header("Content-Type", "application/json");
            HttpRequest request = builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            try {
                HttpResponse<String> response = sharedClient.send(request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                return new AgentBrowserHttpResponse(response.statusCode(), response.body());
            } catch (IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                String message = AgentBrowserCredentialRedactor.redact(e.getMessage(), apiKey);
                throw new IllegalStateException(StringUtils.defaultIfBlank(message, "Kernel HTTP request failed"));
            }
        }

        private static String encodePathSegment(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        }

        private static String trimTrailingSlash(String value) {
            String normalized = StringUtils.defaultIfBlank(value, DEFAULT_BASE_URL).trim();
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        }
    }
}
