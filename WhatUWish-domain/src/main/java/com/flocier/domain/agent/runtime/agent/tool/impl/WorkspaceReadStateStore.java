package com.flocier.domain.agent.runtime.agent.tool.impl;

import com.flocier.domain.agent.runtime.agent.AgentContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 本轮读取范围和内容哈希，用于重复读取去重及安全编辑。 */
final class WorkspaceReadStateStore {
    private static final String PREFIX = "conversation.workspace.read:";

    /** 写入或编辑后刷新文件哈希；0/0 表示没有可复用的读取范围。 */
    void mark(AgentContext context, Path path) throws IOException {
        mark(context, path, 0, 0);
    }

    /** 保存本轮 workspace_read 的文件版本与读取范围。 */
    void mark(AgentContext context, Path path, int startLine, int lineCount) throws IOException {
        context.setAttribute(PREFIX + path, new ReadState(hash(path), startLine, lineCount));
    }

    /** 同一范围且字节未变时可让 Agent 复用上次观察，减少重复正文。 */
    boolean unchanged(AgentContext context, Path path, int startLine, int lineCount) throws IOException {
        ReadState seen = context.getAttribute(PREFIX + path);
        return seen != null && seen.startLine() == startLine && seen.lineCount() == lineCount
                && seen.hash().equals(hash(path));
    }

    /** 编辑前校验已读且文件未变化，避免基于过期观察覆盖更新。 */
    void requireCurrent(AgentContext context, Path path) throws IOException {
        ReadState seen = context.getAttribute(PREFIX + path);
        if (seen == null) throw new IllegalArgumentException("编辑前请先用 workspace_read 读取该文件");
        if (!seen.hash().equals(hash(path))) throw new IllegalArgumentException("文件自上次读取后已变化，请重新用 workspace_read 读取");
    }

    private String hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private record ReadState(String hash, int startLine, int lineCount) { }
}
