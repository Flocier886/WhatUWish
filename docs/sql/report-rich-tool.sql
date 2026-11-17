-- Report Rich Tool 专属执行投影与输入 Artifact 关联。
-- 本项目尚未接入统一迁移框架；部署包含本变更的应用前必须先执行本脚本。

CREATE TABLE IF NOT EXISTS report_execution (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    report_execution_id VARCHAR(64) NOT NULL COMMENT 'Report 专属执行记录标识',
    tool_invocation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '一对一关联的通用工具调用账本标识',
    python_execution_id VARCHAR(160) NULL COMMENT 'Python SSE 执行关联标识',
    run_id VARCHAR(64) NULL COMMENT '所属运行标识',
    session_id VARCHAR(64) NULL COMMENT '所属会话标识',
    request_id VARCHAR(128) NULL COMMENT '所属请求标识',
    task_summary VARCHAR(600) NOT NULL COMMENT '受控报告任务摘要，不保存完整 Prompt',
    output_type VARCHAR(32) NOT NULL COMMENT '输出类型：markdown、html 或 ppt',
    output_file_name VARCHAR(255) NOT NULL COMMENT '请求的输出文件名',
    status VARCHAR(32) NOT NULL COMMENT '当前或终态执行状态',
    input_artifact_count INT NOT NULL DEFAULT 0 COMMENT '已解析输入 Artifact 数量',
    output_artifact_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '已登记的最终输出 Artifact 标识',
    final_summary VARCHAR(1800) NULL COMMENT '受控终态报告摘要',
    error_code VARCHAR(128) NULL COMMENT '受控执行错误码',
    error_message VARCHAR(1200) NULL COMMENT '受控执行错误信息',
    started_at DATETIME NOT NULL COMMENT '专属执行开始时间',
    finished_at DATETIME NULL COMMENT '专属执行终态时间',
    version_no BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发控制版本',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_execution_id (report_execution_id) COMMENT 'Report 专属执行记录唯一标识约束',
    UNIQUE KEY uk_report_execution_tool_invocation (tool_invocation_id) COMMENT '每个工具调用仅有一个 Report 专属执行记录',
    KEY idx_report_execution_run (run_id, started_at) COMMENT '按运行查询 Report 执行的索引',
    KEY idx_report_execution_output_artifact (output_artifact_id) COMMENT '按输出 Artifact 查询 Report 执行的索引',
    -- 外键：关联通用工具调用总账。
    CONSTRAINT fk_report_execution_tool_invocation FOREIGN KEY (tool_invocation_id)
        REFERENCES tool_invocation_ledger (tool_invocation_id),
    -- 外键：关联唯一正式输出产物账本。
    CONSTRAINT fk_report_execution_output_artifact FOREIGN KEY (output_artifact_id)
        REFERENCES artifact_ledger (artifact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Report 专属执行状态与终态投影';

CREATE TABLE IF NOT EXISTS report_execution_input_artifact (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    report_execution_id VARCHAR(64) NOT NULL COMMENT '关联的 Report 专属执行记录标识',
    artifact_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '按用户或 ReAct 显式选择的输入 Artifact 标识',
    input_order INT NOT NULL COMMENT '输入材料在报告上下文中的稳定顺序，从零开始',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_report_execution_input_artifact (report_execution_id, artifact_id) COMMENT '同一 Report 不重复关联同一输入 Artifact 的约束',
    UNIQUE KEY uk_report_execution_input_order (report_execution_id, input_order) COMMENT '同一 Report 输入材料顺序唯一约束',
    KEY idx_report_execution_input_artifact (artifact_id) COMMENT '按输入 Artifact 查询被引用 Report 的索引',
    -- 外键：关联 Report 专属执行记录。
    CONSTRAINT fk_report_input_execution FOREIGN KEY (report_execution_id)
        REFERENCES report_execution (report_execution_id),
    -- 外键：关联真实 Artifact 账本。
    CONSTRAINT fk_report_input_artifact FOREIGN KEY (artifact_id)
        REFERENCES artifact_ledger (artifact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Report 执行与输入 Artifact 的有序关联';
