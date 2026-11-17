package com.flocier.domain.ledger.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 执行过程中的可复用产物账本记录。
 * 只记录可寻址、可追踪、可在后续步骤或回放中复用的对象，例如文件、脚本输出、用户上传或工具生成产物；
 * 普通 LLM 响应摘要和 Tool observation 留在对应 invocation ledger 中。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactLedgerEntity {

    /** 产物唯一标识。 */
    private String artifactId;
    /** 所属执行 run 标识。 */
    private String runId;
    /** 所属会话标识。 */
    private String sessionId;
    /** 所属请求标识。 */
    private String requestId;
    /** 所属链路追踪标识。 */
    private String traceId;
    /** 产物来源 Tool invocation 记录 ID。 */
    private String toolInvocationId;
    /** 产物对应的模型 tool call ID。 */
    private String toolCallId;
    /** 产物角色，例如 input、output、intermediate。 */
    private String artifactRole;
    /** 产物可见性，例如 visible、internal。 */
    private String visibility;
    /** 产物来源类型，例如 tool_output、python_artifact、skill_generated_artifact、user_upload。 */
    private String sourceType;
    /** 产物来源记录标识。 */
    private String sourceId;
    /** 产物来源名称，例如工具名、skill 名或上传通道。 */
    private String sourceName;
    /** 产物类型，例如 file、report、image、dataset、script_output。 */
    private String artifactType;
    /** 产物名称。 */
    private String artifactName;
    /** 文件名。 */
    private String fileName;
    /** 存储类型，例如 file_ref、object_key、url。 */
    private String storageType;
    /** 存储引用。 */
    private String storageRef;
    /** 稳定存储 key。 */
    private String storageKey;
    /** 下载地址。 */
    private String downloadUrl;
    /** 预览地址。 */
    private String previewUrl;
    /** 产物内容摘要。 */
    private String contentSummary;
    /** MIME 类型。 */
    private String mimeType;
    /** 大小信息。 */
    private Long size;
    /** 文件大小，字节为单位。 */
    private Long fileSize;
    /** 文件 hash。 */
    private String fileHash;
    /** 产物原生 metadata JSON。 */
    private String metadataJson;
    /** 软删除标记，0 表示未删除。 */
    private Integer deleted;
    /** 软删除时间。 */
    private Instant deletedAt;
    /** 幂等键。 */
    private String idempotencyKey;
    /** 创建时间。 */
    private Instant createdAt;
    /** 更新时间。 */
    private Instant updatedAt;
    /** 预留扩展字段。 */
    @Builder.Default
    private Map<String, Object> ext = new LinkedHashMap<>();
}
