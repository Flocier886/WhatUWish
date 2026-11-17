package com.flocier.domain.agent.runtime.agent.tool;

import com.flocier.domain.agent.runtime.agent.AgentContext;
import com.flocier.domain.agent.runtime.file.RunWorkspaceService;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 超长工具观察保留全文到会话工作区，只向模型返回可续读的路径。 */
@Slf4j
public final class ToolObservationBudget {
    static final int DEFAULT_CAP = 30_000;
    private static final int PREVIEW_CHARS = 2_000;
    private static final Map<String, Integer> CAPS_BY_TOOL = Map.of(
            "workspace_grep", 20_000,
            "workspace_glob", 20_000,
            "workspace_list", 20_000,
            "WebSearch", 20_000
    );
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private ToolObservationBudget() { }

    public static String apply(AgentContext context, String toolName, String observation) {
        int cap = toolName == null ? DEFAULT_CAP : CAPS_BY_TOOL.getOrDefault(toolName, DEFAULT_CAP);
        if (observation == null || observation.length() <= cap) return observation;
        Path root = context == null ? null : context.getAttribute(RunWorkspaceService.ROOT_ATTRIBUTE);
        if (root == null || toolName == null || toolName.isBlank()) return clipped(observation, cap);
        try {
            Path dir = root.resolve(".cute/observations");
            Files.createDirectories(dir);
            Path file = dir.resolve(toolName + "-" + System.currentTimeMillis()
                    + "-" + SEQUENCE.incrementAndGet() + ".txt");
            Files.writeString(file, observation, StandardCharsets.UTF_8);
            String relative = root.relativize(file).toString().replace('\\', '/');
            return "<persisted-output>\nOutput too large (" + (observation.length() / 1024)
                    + " KB). Full output saved to: " + relative
                    + "\nUse workspace_read(path='" + relative + "', start_line=1) to continue.\n\n"
                    + "Preview (first 2 KB):\n" + observation.substring(0, PREVIEW_CHARS)
                    + "\n</persisted-output>";
        } catch (Exception failure) {
            log.warn("persist oversized observation failed, tool={}", toolName, failure);
            return clipped(observation, cap);
        }
    }

    private static String clipped(String observation, int cap) {
        return observation.substring(0, cap - 24) + "\n[output truncated]";
    }
}
