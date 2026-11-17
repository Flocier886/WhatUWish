package com.flocier.domain.ledger.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Locale;

/**
 * Session 维度的 run 统计快照。
 * 该对象只从 run 账本事实重算得出，避免重复 finish 导致 session 计数漂移。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RunLedgerStats {

    private Integer runCount;
    private Integer finishedRunCount;
    private Integer failedRunCount;

    public static RunLedgerStats empty() {
        return RunLedgerStats.builder()
                .runCount(0)
                .finishedRunCount(0)
                .failedRunCount(0)
                .build();
    }

    public static RunLedgerStats fromRuns(List<RunLedgerEntity> runs) {
        if (runs == null || runs.isEmpty()) {
            return empty();
        }
        int total = 0;
        int completed = 0;
        int failed = 0;
        for (RunLedgerEntity run : runs) {
            if (run == null) {
                continue;
            }
            total++;
            if (isCompletedStatus(run.getStatus())) {
                completed++;
            } else if (isFailedStatus(run.getStatus())) {
                failed++;
            }
        }
        return RunLedgerStats.builder()
                .runCount(total)
                .finishedRunCount(completed)
                .failedRunCount(failed)
                .build();
    }

    public static boolean isCompletedStatus(String status) {
        return "COMPLETED".equals(normalizeStatus(status));
    }

    public static boolean isFailedStatus(String status) {
        String normalized = normalizeStatus(status);
        return "FAILED".equals(normalized)
                || "ERROR".equals(normalized)
                || "CANCELLED".equals(normalized);
    }

    public static boolean isTerminalStatus(String status) {
        return isCompletedStatus(status) || isFailedStatus(status);
    }

    private static String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
    }
}
