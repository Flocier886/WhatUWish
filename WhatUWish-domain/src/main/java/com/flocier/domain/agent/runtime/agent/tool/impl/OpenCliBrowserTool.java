package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.flocier.domain.agent.adapter.port.AgentBrowserSession;
import com.flocier.domain.agent.adapter.port.AgentBrowserSessionPort;
import com.flocier.domain.agent.adapter.port.BrowserRelaySocketPort;
import com.flocier.domain.agent.adapter.port.cli.CliExecutionPort;
import com.flocier.domain.agent.adapter.port.cli.CliInvocation;
import com.flocier.domain.agent.adapter.port.cli.CliResult;
import com.flocier.domain.agent.browser.LocalBrowserOwner;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.agent.tool.cli.OpenCliArgv;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import com.flocier.domain.agent.runtime.file.WorkspaceArtifactRegistrationService;
import com.flocier.types.agent.config.BrowserRelayProperties;
import com.flocier.types.agent.config.OpenCliProperties;
import org.apache.commons.lang3.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;

/**
 * Agent 面向 OpenCLI 的普通工具：本机 browser 经 Relay，agent_browser 经 Kernel CDP。
 * 两条路径共用 argv、CLI 执行和本站 ToolResultPayload；站点命令由外部 OpenCLI 提供。
 */
public final class OpenCliBrowserTool implements BaseTool {
    /** true 表示 Kernel 远端会话；false 表示当前 owner 已配对的本机浏览器。 */
    private final boolean remote;
    private final CliExecutionPort cli;
    private final OpenCliProperties opencli;
    private final BrowserRelayProperties relayProperties;
    private final BrowserRelaySocketPort relay;
    private final AgentBrowserSessionPort sessions;
    private final LocalBrowserOwner owner;
    private final RunWorkspaceService workspace;
    private final WorkspaceArtifactRegistrationService artifacts;
    private final String relayUrl;

    public OpenCliBrowserTool(boolean remote, CliExecutionPort cli, OpenCliProperties opencli,
                              BrowserRelayProperties relayProperties, BrowserRelaySocketPort relay,
                              AgentBrowserSessionPort sessions, LocalBrowserOwner owner,
                              RunWorkspaceService workspace, WorkspaceArtifactRegistrationService artifacts,
                              String relayUrl) {
        this.remote = remote;
        this.cli = cli;
        this.opencli = opencli;
        this.relayProperties = relayProperties;
        this.relay = relay;
        this.sessions = sessions;
        this.owner = owner;
        this.workspace = workspace;
        this.artifacts = artifacts;
        this.relayUrl = relayUrl;
    }

    @Override public String name() { return remote ? "agent_browser" : "browser"; }
    @Override public String description() {
        return remote
                ? "通过 OpenCLI 操作当前用户的 Agent 云端浏览器；云端登录态与本机浏览器独立。"
                : "通过 OpenCLI 操作已连接的用户浏览器，调用当前浏览器可访问的站点命令。";
    }

    @Override public Object params() {
        return Map.of("type", "object", "properties", Map.of(
                "args", Map.of("type", "array", "items", Map.of("type", "string"),
                        "description", "CLI argv 参数数组，每项单独传入。未知站点名用 [--help] 看站点索引；已知站点用 [<site>, --help] 看命令，再用 [<site>, <command>, --help, -f, yaml] 看参数。list 输出全部站点详细说明，可能被截断。"),
                "timeout_ms", Map.of("type", "integer", "description", "超时毫秒，默认 180000")),
                "required", List.of("args"));
    }

    /**
     * 将 Agent 提供的参数作为 argv 交给 OpenCLI，不通过 shell。
     * 结果保留退出码、标准输出/错误和超时事实；敏感命令在返回 Ledger 前裁掉正文。
     */
    @Override public Object execute(Object input, AgentContext context) {
        if (!(input instanceof ToolInvokeRequest request) || context == null) return failure("browser_invalid_request", "浏览器工具参数无效");
        List<String> args = strings(request.getArguments() == null ? null : request.getArguments().get("args"));
        if (args.isEmpty()) return failure("browser_invalid_request", "args 不能为空");
        if (!opencli.isEnabled() || !cli.isResolvable(opencli.getCommand())) return failure("opencli_unavailable", "OpenCLI 不可用");
        String userId = owner.requireOwnerId();
        Map<String, String> env = new LinkedHashMap<>();
        String cdp = null;
        boolean reconstructed = false;
        // 远端只向子进程传 CDP 地址；本机则传 Relay RPC 地址和当前单用户归属。
        if (remote) {
            if (sessions == null || !sessions.isConfigured()) return failure("agent_browser_unavailable", "Agent 浏览器未配置");
            try {
                AgentBrowserSession session = sessions.resolveForUser(userId);
                if (session == null || StringUtils.isBlank(session.getCdpWsUrl())) return failure("agent_browser_unavailable", "Agent 浏览器会话不可用");
                cdp = session.getCdpWsUrl();
                reconstructed = session.isReconstructed();
                env.put("OPENCLI_CDP_ENDPOINT", cdp);
            } catch (RuntimeException e) { return failure("agent_browser_session_failed", "获取 Agent 浏览器会话失败"); }
        } else {
            if (relay == null || !relay.isOnline(userId)) return failure("browser_offline", "浏览器未连接");
            env.put("OPENCLI_RELAY_URL", StringUtils.defaultIfBlank(opencli.getRelayUrl(), relayUrl));
            env.put("OPENCLI_RELAY_VISITOR_ID", userId);
            if (StringUtils.isNotBlank(relayProperties.getInternalRpcSecret())) env.put("OPENCLI_RELAY_SECRET", relayProperties.getInternalRpcSecret());
        }
        String cacheRoot = StringUtils.defaultIfBlank(opencli.getCacheDir(), Path.of(System.getProperty("java.io.tmpdir"), "reactor-opencli").toString());
        env.put("OPENCLI_CACHE_DIR", Path.of(cacheRoot, userId).toString());
        List<String> rewritten = OpenCliArgv.rewrite(args, opencli.getExtraArgs());
        Path screenshotPath;
        try {
            screenshotPath = screenshotPath(context, rewritten);
        } catch (Exception e) {
            return failure("browser_invalid_screenshot_path", "截图路径必须位于当前工作区内");
        }
        List<String> argv = new ArrayList<>(opencli.getPrefixArgs() == null ? List.of() : opencli.getPrefixArgs());
        argv.addAll(rewritten);
        long timeout = timeout(request.getArguments().get("timeout_ms"));
        CliResult result;
        try {
            result = cli.exec(CliInvocation.builder().tool(opencli.getCommand()).args(argv)
                    .cwd(workspace.root(context).toString()).env(env)
                    .unsetEnv(remote ? List.of("KERNEL_API_KEY") : List.of())
                    .timeoutMs(timeout).capture("both").maxOutputChars(opencli.getMaxOutputChars()).build());
        } catch (RuntimeException e) { return failure("opencli_execution_failed", "OpenCLI 执行失败"); }
        if (result == null) return failure("opencli_execution_failed", "OpenCLI 未返回结果");

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ok", result.isOk()); fields.put("tool", name()); fields.put("exit_code", result.getExitCode());
        fields.put("stdout", redact(result.getStdout(), cdp)); fields.put("stderr", redact(result.getStderr(), cdp));
        fields.put("duration_ms", result.getDurationMs()); fields.put("timed_out", result.isTimedOut());
        fields.put("truncated", result.isTruncated());
        if (reconstructed) fields.put("notice", "Agent 浏览器已重建，之前的页面状态已丢失。");
        List<String> artifactRefs = screenshotPath == null ? screenshot(context, request, fields)
                : registerScreenshot(context, request, fields, screenshotPath, result.isOk());
        if (OpenCliArgv.isSensitive(rewritten)) {
            // 本站 Ledger 会同时持久化 observation；敏感命令只留下状态与长度。
            fields.put("stdout_chars", StringUtils.length(result.getStdout()));
            fields.put("redacted", OpenCliArgv.sensitiveKind(rewritten));
            fields.remove("stdout"); fields.remove("stderr");
        }
        String output = JSON.toJSONString(fields);
        return ToolResultPayload.builder().success(result.isOk())
                .status(result.isOk() ? "success" : "failed")
                .errorCode(result.isOk() ? null : result.isTimedOut() ? "browser_timeout" : "opencli_execution_failed")
                .errorMessage(result.isOk() ? null : "OpenCLI 退出码为 " + result.getExitCode())
                .toolResult(output).llmObservation(output).structuredOutput(fields).artifactRefs(artifactRefs).build();
    }

    /** 通用 browser screenshot 在固定版 OpenCLI 中将 PNG 写到指定路径。 */
    private Path screenshotPath(AgentContext context, List<String> args) throws Exception {
        if (args.size() < 3 || !"browser".equals(args.get(0)) || !"screenshot".equals(args.get(2))
                || args.contains("--help") || args.contains("-h"))
            return null;
        String relative = null;
        for (int i = 3; i < args.size(); i++) {
            String arg = args.get(i);
            if (List.of("--tab", "--width", "--height").contains(arg)) {
                i++;
            } else if (!arg.startsWith("-")) {
                relative = arg;
            }
        }
        if (relative == null) {
            relative = "outputs/browser-" + UUID.randomUUID() + ".png";
            args.add(relative);
        }
        Path path = workspace.writable(context, relative);
        Files.createDirectories(path.getParent());
        return path;
    }

    private List<String> registerScreenshot(AgentContext context, ToolInvokeRequest request,
                                            Map<String, Object> fields, Path path, boolean succeeded) {
        if (!succeeded) return List.of();
        try {
            Map<String, Object> registered = artifacts.registerBrowserScreenshot(context, path,
                    request.getToolCallId(), name());
            fields.put("screenshot", Map.of("artifactId", registered.get("artifactId"), "name", registered.get("name")));
            return List.of(String.valueOf(registered.get("artifactId")));
        } catch (Exception e) {
            fields.put("screenshot_error", "截图产物登记失败");
            return List.of();
        }
    }

    /**
     * OpenCLI 若返回截图 base64，则移出 observation，写入会话 outputs 并登记 Artifact。
     * 普通站点 JSON（包括 data 数组）保持原样；登记失败只标记截图失败，不伪造 artifactId。
     */
    private List<String> screenshot(AgentContext context, ToolInvokeRequest request, Map<String, Object> fields) {
        Object stdout = fields.get("stdout");
        if (!(stdout instanceof String text)) return List.of();
        JSONObject json;
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) return List.of();
        try { json = JSON.parseObject(trimmed.substring(start, end + 1)); } catch (RuntimeException e) { return List.of(); }
        if (json == null) return List.of();
        Object nested = json.get("data");
        JSONObject data = nested instanceof JSONObject object ? object : null;
        JSONObject source = data == null ? json : data;
        String image = source.getString("imageBase64");
        if (StringUtils.isBlank(image)) return List.of();
        source.remove("imageBase64");
        json.remove("imageBase64");
        fields.put("stdout", json.toJSONString());
        try {
            byte[] bytes = Base64.getDecoder().decode(image);
            if (bytes.length < 8 || bytes.length > 32 * 1024 * 1024 ||
                    !Arrays.equals(Arrays.copyOf(bytes, 8), new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 13, 10, 26, 10}))
                throw new IllegalArgumentException("截图不是有效 PNG");
            Path path = workspace.writable(context, "outputs/browser-" + UUID.randomUUID() + ".png");
            Files.write(path, bytes);
            Map<String, Object> registered = artifacts.registerBrowserScreenshot(context, path, request.getToolCallId(), name());
            fields.put("screenshot", Map.of("artifactId", registered.get("artifactId"), "name", registered.get("name")));
            return List.of(String.valueOf(registered.get("artifactId")));
        } catch (Exception e) {
            fields.put("screenshot_error", "截图产物登记失败");
            return List.of();
        }
    }

    private long timeout(Object raw) {
        long max = Math.max(1, opencli.getMaxTimeoutMs());
        long value = raw instanceof Number n ? n.longValue() : opencli.getDefaultTimeoutMs();
        return Math.min(value > 0 ? value : opencli.getDefaultTimeoutMs(), max);
    }

    private static String redact(String text, String secret) {
        return text == null ? "" : StringUtils.isBlank(secret) ? text : text.replace(secret, "[redacted]");
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) { if (!(item instanceof String s)) return List.of(); out.add(s); }
        return out;
    }

    private static ToolResultPayload failure(String code, String message) {
        Map<String, Object> fields = Map.of("ok", false, "error", message);
        String output = JSON.toJSONString(fields);
        return ToolResultPayload.builder().success(false).status("failed").errorCode(code).errorMessage(message)
                .toolResult(output).llmObservation(output).structuredOutput(fields).build();
    }
}
