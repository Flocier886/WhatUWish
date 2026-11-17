package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.alibaba.fastjson.JSON;
import com.flocier.domain.agent.adapter.port.cli.CliExecutionPort;
import com.flocier.domain.agent.adapter.port.cli.CliInvocation;
import com.flocier.domain.agent.adapter.port.cli.CliResult;
import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.agent.tool.BaseTool;
import com.flocier.domain.agent.runtime.agent.tool.ToolInvokeRequest;
import com.flocier.domain.agent.runtime.agent.tool.ToolResultPayload;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import com.flocier.domain.agent.runtime.file.WorkspaceArtifactRegistrationService;
import com.flocier.types.agent.config.HostCliProperties;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** 按 master 的 host_cli 合同调用宿主 CLI；参数始终作为 argv 传递。 */
public class HostCliTool implements BaseTool {

    public static final String TOOL_NAME = "host_cli";
    private static final Set<String> TWITTER_WRITE_COMMANDS = Set.of(
            "bookmark", "delete", "favorite", "follow", "like", "post", "quote",
            "reply", "retweet", "unbookmark", "unfavorite", "unfollow", "unlike", "unretweet");
    private static final Set<String> REDDIT_WRITE_COMMANDS = Set.of(
            "comment", "login", "logout", "save", "subscribe", "upvote");
    private static final List<String> PROXY_ENVIRONMENT_VARIABLES = List.of(
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy");
    private static final int MAX_ARTIFACT_SCAN_DEPTH = 4;

    private final List<String> availableTools;
    private final CliExecutionPort cliExecutionPort;
    private final HostCliProperties properties;
    private final ReactorConfig reactorConfig;
    private final RunWorkspaceService workspace;
    private final WorkspaceArtifactRegistrationService artifactRegistration;

    public HostCliTool(List<String> availableTools, CliExecutionPort cliExecutionPort,
                       HostCliProperties properties, ReactorConfig reactorConfig,
                       RunWorkspaceService workspace,
                       WorkspaceArtifactRegistrationService artifactRegistration) {
        this.availableTools = availableTools == null ? List.of() : List.copyOf(availableTools);
        this.cliExecutionPort = cliExecutionPort;
        this.properties = properties;
        this.reactorConfig = reactorConfig;
        this.workspace = workspace;
        this.artifactRegistration = artifactRegistration;
    }

    @Override public String name() { return TOOL_NAME; }

    @Override
    public String description() {
        return "在当前会话工作区运行已安装的宿主 CLI，不经 shell。可用程序："
                + String.join(", ", availableTools)
                + "。参数按 CLI 原生 argv 提供；twitter 与 rdt 的已知写入命令会被拒绝。"
                + "宿主 CLI 不共享已连接浏览器的登录态；需要使用浏览器登录态访问站点时，若 browser 可用，应优先使用 browser。";
    }

    @Override
    public Object params() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("tool", Map.of("type", "string", "description", "白名单中的 CLI 程序名",
                "enum", availableTools));
        fields.put("args", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "CLI 原生参数数组；禁止传入整段 shell 命令"));
        fields.put("timeout_ms", Map.of("type", "integer", "description", "超时毫秒，可选"));
        return Map.of("type", "object", "properties", fields, "required", List.of("tool", "args"));
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        if (!(input instanceof ToolInvokeRequest request)) {
            return failure("host_cli_invalid_request", "CLI 工具参数格式无效", Map.of());
        }
        Map<String, Object> argsMap = request.getArguments() == null ? Map.of() : request.getArguments();
        String tool = stringValue(argsMap.get("tool"));
        List<String> args = stringList(argsMap.get("args"));
        if (StringUtils.isBlank(tool)) {
            return failure("host_cli_invalid_request", "tool 不能为空", Map.of());
        }
        tool = tool.trim();
        if (!availableTools.contains(tool)) {
            return failure("host_cli_denied", "不允许的 CLI: " + tool, Map.of("tool", tool));
        }
        String deniedCommand = deniedSocialCommand(tool, args);
        if (deniedCommand != null) {
            return failure("host_cli_denied", "不允许的只读 CLI 操作: " + tool + " " + deniedCommand,
                    Map.of("tool", tool, "denied_command", deniedCommand));
        }
        if (context == null || cliExecutionPort == null || properties == null
                || !properties.isEnabled() || !cliExecutionPort.isResolvable(tool)) {
            return failure("host_cli_unavailable", "CLI 不可用: " + tool, Map.of("tool", tool));
        }

        Path cwd;
        try {
            cwd = workspace.root(context);
        } catch (Exception unavailable) {
            return failure("host_cli_workspace_unavailable", "当前会话工作区未就绪", Map.of("tool", tool));
        }
        Set<String> before = snapshot(cwd);
        String proxy = reactorConfig == null ? null : StringUtils.trimToNull(reactorConfig.getWebFetchProxy());
        CliResult result = cliExecutionPort.exec(CliInvocation.builder()
                .tool(tool)
                .args(args)
                .cwd(cwd.toAbsolutePath().toString())
                .env(proxyEnvironment(proxy))
                .unsetEnv(proxy == null ? PROXY_ENVIRONMENT_VARIABLES : List.of())
                .timeoutMs(resolveTimeout(argsMap.get("timeout_ms")))
                .capture("both")
                .maxOutputChars(properties.getMaxOutputChars())
                .build());
        if (result == null) {
            return failure("host_cli_execution_failed", "CLI 执行器未返回结果", Map.of("tool", tool));
        }

        List<Map<String, Object>> artifacts = registerNewFiles(context, request, cwd, before);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ok", result.isOk());
        fields.put("tool", tool);
        fields.put("exit_code", result.getExitCode());
        fields.put("stdout", StringUtils.defaultString(result.getStdout()));
        fields.put("stderr", StringUtils.defaultString(result.getStderr()));
        fields.put("duration_ms", result.getDurationMs());
        fields.put("timed_out", result.isTimedOut());
        fields.put("truncated", result.isTruncated());
        if (!artifacts.isEmpty()) fields.put("artifacts", artifacts);

        String output = JSON.toJSONString(fields);
        return ToolResultPayload.builder()
                .success(result.isOk())
                .status(result.isOk() ? "success" : "failed")
                .errorCode(result.isOk() ? null : result.isTimedOut() ? "host_cli_timeout" : "host_cli_execution_failed")
                .errorMessage(result.isOk() ? null : failureMessage(result))
                .toolResult(output)
                .llmObservation(output)
                .structuredOutput(fields)
                .artifactRefs(artifacts.stream()
                        .filter(item -> Boolean.TRUE.equals(item.get("registered")))
                        .map(item -> String.valueOf(item.get("artifactId")))
                        .toList())
                .build();
    }

    private List<Map<String, Object>> registerNewFiles(AgentContext context, ToolInvokeRequest request,
                                                        Path cwd, Set<String> before) {
        List<Map<String, Object>> artifacts = new ArrayList<>();
        for (String fileName : snapshot(cwd)) {
            if (before.contains(fileName)) continue;
            Path file = Path.of(fileName);
            try {
                workspace.writable(context, workspace.relative(context, file));
                Map<String, Object> registered = artifactRegistration.registerHostCliOutput(
                        context, file, request.getToolCallId());
                Map<String, Object> artifact = new LinkedHashMap<>();
                artifact.put("path", registered.get("path"));
                artifact.put("artifactId", registered.get("artifactId"));
                artifact.put("name", registered.get("name"));
                artifact.put("bytes", registered.get("bytes"));
                artifact.put("registered", Boolean.TRUE);
                artifacts.add(artifact);
            } catch (Exception ignored) {
                Map<String, Object> artifact = new LinkedHashMap<>();
                try {
                    artifact.put("path", workspace.relative(context, file));
                } catch (Exception pathFailure) {
                    artifact.put("path", file.getFileName().toString());
                }
                artifact.put("name", file.getFileName().toString());
                artifact.put("registered", Boolean.FALSE);
                artifact.put("registration_error", "Artifact 登记失败；文件仍保留在会话工作区");
                artifacts.add(artifact);
            }
        }
        return artifacts;
    }

    private static Set<String> snapshot(Path cwd) {
        if (cwd == null || !Files.isDirectory(cwd, LinkOption.NOFOLLOW_LINKS)) return Set.of();
        Set<String> files = new LinkedHashSet<>();
        try (Stream<Path> walk = Files.walk(cwd, MAX_ARTIFACT_SCAN_DEPTH)) {
            walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .forEach(path -> files.add(path.toAbsolutePath().normalize().toString()));
        } catch (IOException ignored) {
        }
        return files;
    }

    private static Map<String, String> proxyEnvironment(String proxy) {
        if (proxy == null) return Map.of();
        return Map.of("HTTP_PROXY", proxy, "HTTPS_PROXY", proxy, "ALL_PROXY", proxy,
                "http_proxy", proxy, "https_proxy", proxy, "all_proxy", proxy);
    }

    private long resolveTimeout(Object raw) {
        long fallback = properties.getDefaultTimeoutMs() <= 0 ? 120_000L : properties.getDefaultTimeoutMs();
        long max = properties.getMaxTimeoutMs() <= 0 ? 300_000L : properties.getMaxTimeoutMs();
        if (raw instanceof Number number && number.longValue() > 0) return Math.min(number.longValue(), max);
        return Math.min(fallback, max);
    }

    private static String failureMessage(CliResult result) {
        if (result.isTimedOut()) return "CLI 执行超时";
        if (StringUtils.isNotBlank(result.getStderr())) return result.getStderr();
        return "CLI 退出码为 " + result.getExitCode();
    }

    private static ToolResultPayload failure(String errorCode, String message, Map<String, Object> details) {
        Map<String, Object> fields = new LinkedHashMap<>(details);
        fields.put("ok", false);
        fields.put("error", message);
        String output = JSON.toJSONString(fields);
        return ToolResultPayload.builder().success(false).status("failed").errorCode(errorCode)
                .errorMessage(message).toolResult(output).llmObservation(output).structuredOutput(fields).build();
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> values = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String text)) return List.of();
            values.add(text);
        }
        return values;
    }

    private static String stringValue(Object raw) { return raw == null ? null : String.valueOf(raw).trim(); }

    private static String deniedSocialCommand(String tool, List<String> args) {
        if (!("twitter".equals(tool) || "rdt".equals(tool))) return null;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) continue;
            String normalized = arg.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("-")) continue;
            if ("twitter".equals(tool) && TWITTER_WRITE_COMMANDS.contains(normalized)) return normalized;
            if ("rdt".equals(tool) && REDDIT_WRITE_COMMANDS.contains(normalized)) return normalized;
            return null;
        }
        return null;
    }
}
