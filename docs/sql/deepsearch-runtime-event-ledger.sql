-- DeepSearch 运行事实账本与专属执行投影。
-- 本项目尚未接入统一迁移框架；部署包含本变更的应用前必须先执行本脚本。

CREATE TABLE runtime_event_ledger (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    runtime_event_id VARCHAR(64) NOT NULL COMMENT '运行事实唯一标识',
    run_id VARCHAR(64) NULL COMMENT '所属运行标识',
    session_id VARCHAR(64) NULL COMMENT '所属会话标识',
    request_id VARCHAR(128) NULL COMMENT '所属请求标识',
    tool_invocation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '所属通用工具调用账本标识',
    sequence_no INT NOT NULL COMMENT '同一工具调用内的上游事件正序号',
    upstream_event_id VARCHAR(80) NOT NULL COMMENT '上游 Python 事件唯一标识，用于幂等去重',
    event_type VARCHAR(64) NOT NULL COMMENT '受控运行事实类型',
    stage VARCHAR(64) NOT NULL COMMENT 'Rich Tool 领域阶段',
    status VARCHAR(32) NOT NULL COMMENT '阶段执行状态',
    occurred_at DATETIME NOT NULL COMMENT '上游事件发生时间',
    recorded_at DATETIME NOT NULL COMMENT 'Java 成功记录事实的时间',
    message VARCHAR(1200) NOT NULL COMMENT '受控阶段摘要，不保存正文或原始响应',
    presentation_kind VARCHAR(64) NULL COMMENT '展示补充数据类型',
    payload_schema_version VARCHAR(32) NULL COMMENT '展示补充数据的结构版本',
    payload_json TEXT NULL COMMENT '受控且限长的展示补充数据 JSON',
    artifact_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '关联的既有 artifact 账本标识',
    error_code VARCHAR(128) NULL COMMENT '受控错误码',
    error_message VARCHAR(1200) NULL COMMENT '受控错误信息',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_runtime_event_id (runtime_event_id) COMMENT '运行事实唯一标识约束',
    UNIQUE KEY uk_runtime_event_invocation_sequence (tool_invocation_id, sequence_no) COMMENT '同一调用内事件顺序唯一约束',
    UNIQUE KEY uk_runtime_event_invocation_upstream (tool_invocation_id, upstream_event_id) COMMENT '同一调用内上游事件幂等唯一约束',
    KEY idx_runtime_event_run_invocation (run_id, tool_invocation_id, sequence_no) COMMENT '按运行和调用顺序读取事实的索引',
    KEY idx_runtime_event_artifact (artifact_id) COMMENT '按关联产物查询运行事实的索引',
    -- 外键：关联通用工具调用总账。
    CONSTRAINT fk_runtime_event_tool_invocation FOREIGN KEY (tool_invocation_id)
        REFERENCES tool_invocation_ledger (tool_invocation_id),
    -- 外键：关联唯一真实产物账本。
    CONSTRAINT fk_runtime_event_artifact FOREIGN KEY (artifact_id)
        REFERENCES artifact_ledger (artifact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Rich Tool 通用追加式运行事实账本';

CREATE TABLE deep_search_execution (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    deep_search_execution_id VARCHAR(64) NOT NULL COMMENT 'DeepSearch 专属执行记录标识',
    tool_invocation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '一对一关联的通用工具调用账本标识',
    python_execution_id VARCHAR(160) NULL COMMENT 'Python SSE 执行关联标识',
    run_id VARCHAR(64) NULL COMMENT '所属运行标识',
    session_id VARCHAR(64) NULL COMMENT '所属会话标识',
    request_id VARCHAR(128) NULL COMMENT '所属请求标识',
    query_text VARCHAR(600) NOT NULL COMMENT 'DeepSearch 研究主题',
    research_mode VARCHAR(32) NOT NULL COMMENT '研究深度模式',
    status VARCHAR(32) NOT NULL COMMENT '当前或终态执行状态',
    final_summary VARCHAR(1800) NULL COMMENT '受控终态研究摘要',
    report_artifact_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '已登记的研究报告产物标识',
    error_code VARCHAR(128) NULL COMMENT '受控执行错误码',
    error_message VARCHAR(1200) NULL COMMENT '受控执行错误信息',
    last_sequence_no INT NOT NULL DEFAULT 0 COMMENT '最后成功消费的上游事件序号',
    last_upstream_event_id VARCHAR(80) NULL COMMENT '最后成功消费的上游事件标识',
    schema_version VARCHAR(32) NULL COMMENT '最后消费的 Python 事件协议版本',
    started_at DATETIME NOT NULL COMMENT '专属执行开始时间',
    finished_at DATETIME NULL COMMENT '专属执行终态时间',
    version_no BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发控制版本',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_deep_search_execution_id (deep_search_execution_id) COMMENT '专属执行记录唯一标识约束',
    UNIQUE KEY uk_deep_search_tool_invocation (tool_invocation_id) COMMENT '每个工具调用仅有一个 DeepSearch 专属执行记录',
    KEY idx_deep_search_run (run_id, started_at) COMMENT '按运行查询 DeepSearch 执行的索引',
    KEY idx_deep_search_report_artifact (report_artifact_id) COMMENT '按报告产物查询执行记录的索引',
    -- 外键：关联通用工具调用总账。
    CONSTRAINT fk_deep_search_tool_invocation FOREIGN KEY (tool_invocation_id)
        REFERENCES tool_invocation_ledger (tool_invocation_id),
    -- 外键：关联唯一真实报告产物账本。
    CONSTRAINT fk_deep_search_report_artifact FOREIGN KEY (report_artifact_id)
        REFERENCES artifact_ledger (artifact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='DeepSearch 专属执行状态与终态投影';
