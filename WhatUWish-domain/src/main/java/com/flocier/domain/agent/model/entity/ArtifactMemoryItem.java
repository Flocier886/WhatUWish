package com.flocier.domain.agent.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 重要产物记忆项。
 * 用于从 Ledger 产物账本中提取可复用的文档、文件与结果索引。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactMemoryItem {

    /** 记忆项唯一标识。 */
    private String memoryId;
    /** 所属会话标识。 */
    private String sessionId;
    /** 所属执行 run 标识。 */
    private String runId;
    /** 对应的账本产物标识。 */
    private String artifactId;
    /** 产物类型，如 file、report、image、dataset、script_output。 */
    private String artifactType;
    /** 产物名称。 */
    private String artifactName;
    /** 产物存储引用。 */
    private String storageRef;
    /** 产物用途摘要。 */
    private String usageSummary;
    /** 来源工具名或来源类型。 */
    private String sourceName;
    /** 记忆项创建时间。 */
    private Instant createdAt;
}
