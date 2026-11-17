package com.flocier.domain.agent.runtime.agent.tool.cli;

import java.util.ArrayList;
import java.util.List;

/** 从 master 搬用 OpenCLI 的 JSON 格式补全与敏感命令判断。 */
public final class OpenCliArgv {
    private OpenCliArgv() { }

    public static List<String> rewrite(List<String> args, List<String> extraArgs) {
        List<String> out = new ArrayList<>(args == null ? List.of() : args);
        if (out.isEmpty()) return out;
        // OpenCLI 帮助有独立的紧凑文本格式；强制 JSON 会展开整站命令详情。
        if (!"browser".equals(out.get(0)) && !isHelp(out) && !hasFormat(out) && extraArgs != null) out.addAll(extraArgs);
        return out;
    }

    public static boolean isSensitive(List<String> args) {
        if (args == null) return false;
        for (String arg : args) {
            if ("cookies".equals(arg) || "eval".equals(arg) || "exec".equals(arg)) return true;
        }
        return false;
    }

    public static String sensitiveKind(List<String> args) {
        if (args != null) {
            for (String arg : args) {
                if ("eval".equals(arg)) return "eval";
                if ("exec".equals(arg)) return "exec";
                if ("cookies".equals(arg)) return "cookies";
            }
        }
        return "cookies";
    }

    private static boolean hasFormat(List<String> args) {
        for (String arg : args) {
            if ("-f".equals(arg) || "--format".equals(arg) || arg != null && arg.startsWith("--format=")) return true;
        }
        return false;
    }

    private static boolean isHelp(List<String> args) {
        return args.contains("--help") || args.contains("-h");
    }
}
