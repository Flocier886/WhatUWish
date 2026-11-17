package com.flocier.domain.agent.runtime.memory.review;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CancellationException;

/** 单次复盘的私有状态；文件锁只覆盖短读写，取消后禁止新的文件操作。 */
public class SkillReviewState {
    /** 库内操作使用同一短锁，保证跨会话的版本检查与写入不可交错。 */
    public static final Object FILE_LOCK = new Object();
    public record ReadVersion(String hash, long modifiedMillis) { }
    /** 只保存包名和包内路径，不在执行记录暴露宿主绝对路径。 */
    public record ChangedFile(String skillName, String path, String changeType) { }
    private final Map<Path, ReadVersion> reads = new HashMap<>();
    private final Map<Path, ChangedFile> changes = new LinkedHashMap<>();
    private final Set<String> unresolvedTools = new HashSet<>();
    private volatile boolean cancelled;

    public void checkActive() {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException("Skill 复盘已取消");
    }
    /** 与已开始的文件写入协调后设置取消，返回时变更快照已稳定。 */
    public void cancel() { synchronized (FILE_LOCK) { cancelled = true; } }
    public ReadVersion version(Path path) { return reads.get(path); }
    public void remember(Path path, ReadVersion version) { reads.put(path, version); }
    public void changed(Path path, String skill, String relative, boolean created) {
        changes.putIfAbsent(path, new ChangedFile(skill, relative, created ? "created" : "updated"));
    }
    public List<ChangedFile> changes() { synchronized (FILE_LOCK) { return List.copyOf(changes.values()); } }
    /** 注册核验需要实际路径；此快照只供服务端使用，审计仍只保存包内路径。 */
    public Map<Path, ChangedFile> changedPaths() { synchronized (FILE_LOCK) { return Map.copyOf(changes); } }
    /** 同名工具后续成功才视为已恢复错误；终答不能自动抹掉失败。 */
    public synchronized void toolResult(String name, boolean success) {
        if (success) unresolvedTools.remove(name); else unresolvedTools.add(name);
    }
    public synchronized boolean hasUnresolvedTools() { return !unresolvedTools.isEmpty(); }
}
