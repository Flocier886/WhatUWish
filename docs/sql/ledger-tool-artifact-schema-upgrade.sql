-- Ledger tool/artifact 表结构升级。
-- 当前项目暂未接入正式数据库迁移框架，因此本文件作为增量 DDL 参考。
-- 部署包含新映射字段的代码前，需要先在目标数据库执行本文件。

ALTER TABLE tool_invocation_ledger
    ADD COLUMN tool_call_id VARCHAR(128) NULL COMMENT '模型或运行时工具调用编号' AFTER tool_invocation_id;

CREATE INDEX idx_tool_invocation_tool_call_id
    ON tool_invocation_ledger (tool_call_id);

ALTER TABLE artifact_ledger
    ADD COLUMN tool_invocation_id VARCHAR(64) NULL COMMENT '产物来源工具调用记录编号' AFTER trace_id,
    ADD COLUMN tool_call_id VARCHAR(128) NULL COMMENT '模型或运行时工具调用编号' AFTER tool_invocation_id,
    ADD COLUMN artifact_role VARCHAR(32) NULL COMMENT '产物角色：输入、输出或中间产物' AFTER tool_call_id,
    ADD COLUMN visibility VARCHAR(32) NULL COMMENT '产物可见性：可见或内部使用' AFTER artifact_role,
    ADD COLUMN source_name VARCHAR(128) NULL COMMENT '来源展示名称，例如工具名或技能名' AFTER source_id,
    ADD COLUMN file_name VARCHAR(255) NULL COMMENT '产物文件名' AFTER artifact_name,
    ADD COLUMN storage_key VARCHAR(512) NULL COMMENT '稳定存储标识' AFTER storage_ref,
    ADD COLUMN download_url VARCHAR(1024) NULL COMMENT '下载地址' AFTER storage_key,
    ADD COLUMN preview_url VARCHAR(1024) NULL COMMENT '预览地址' AFTER download_url,
    ADD COLUMN file_size BIGINT NULL COMMENT '文件字节数' AFTER size,
    ADD COLUMN file_hash VARCHAR(128) NULL COMMENT '文件内容哈希' AFTER file_size,
    ADD COLUMN metadata_json TEXT NULL COMMENT '产物原生元数据文本' AFTER file_hash,
    ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记，0 未删除，1 已删除' AFTER metadata_json,
    ADD COLUMN deleted_at DATETIME NULL COMMENT '软删除时间' AFTER deleted;

CREATE INDEX idx_artifact_tool_invocation_id
    ON artifact_ledger (tool_invocation_id);

CREATE INDEX idx_artifact_run_tool_call
    ON artifact_ledger (run_id, tool_call_id);

CREATE INDEX idx_artifact_request_tool_call
    ON artifact_ledger (request_id, tool_call_id);

CREATE INDEX idx_artifact_deleted
    ON artifact_ledger (deleted);
