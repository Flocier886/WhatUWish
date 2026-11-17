-- 启用 memory.working 前执行；字段设计参考 ai-agent-master-02 的工作记忆表。
-- 本项目的 runId 为字符串，因此 run_id 使用 VARCHAR，与 master 的 BIGINT 不同。
-- 已执行过旧版无注释建表脚本的库，另执行 react-working-memory-comments.sql。

CREATE TABLE IF NOT EXISTS ai_agent_working_memory_turn (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '工作记忆轮次主键',
    session_id VARCHAR(64) NOT NULL COMMENT '所属会话标识',
    memory_scope VARCHAR(64) NOT NULL DEFAULT 'main' COMMENT '记忆作用域，当前主 ReAct 固定为 main',
    request_id VARCHAR(128) NOT NULL COMMENT '产生本轮消息的请求标识；压缩轮次使用独立标识',
    run_id VARCHAR(64) NULL COMMENT '所属运行标识；运行中压缩轮次可为空',
    turn_seq INT NOT NULL COMMENT '同一会话及作用域内的轮次顺序号',
    entry_agent VARCHAR(32) NOT NULL COMMENT '写入入口，当前为 react 或 compaction',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '轮次状态：0 构建中，1 可注入，2 已被压缩投影替代',
    schema_version INT NOT NULL DEFAULT 1 COMMENT '工作记忆投影结构版本',
    message_count INT NOT NULL DEFAULT 0 COMMENT '本轮持久化的消息数量',
    token_estimate INT NOT NULL DEFAULT 0 COMMENT '本轮消息的粗略 token 估算值',
    started_at DATETIME(3) NULL COMMENT '轮次开始写入时间',
    finished_at DATETIME(3) NULL COMMENT '轮次完成写入时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录更新时间',
    deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记：0 未删除，1 已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wm_turn_request (request_id, deleted) COMMENT '按请求标识幂等写入轮次',
    UNIQUE KEY uk_wm_turn_session_scope_seq (session_id, memory_scope, turn_seq, deleted) COMMENT '约束会话内轮次顺序',
    KEY idx_wm_turn_session (session_id, deleted, turn_seq DESC) COMMENT '按会话读取轮次',
    KEY idx_wm_turn_session_scope (session_id, memory_scope, deleted, turn_seq DESC) COMMENT '按作用域读取轮次',
    KEY idx_wm_turn_run (run_id, deleted) COMMENT '按运行追溯轮次'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话工作记忆轮次；READY 组成当前可注入投影，INVALID 保留压缩前历史';

CREATE TABLE IF NOT EXISTS ai_agent_working_memory_message (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '工作记忆消息主键',
    session_id VARCHAR(64) NOT NULL COMMENT '所属会话标识',
    memory_scope VARCHAR(64) NOT NULL DEFAULT 'main' COMMENT '记忆作用域，当前主 ReAct 固定为 main',
    turn_id BIGINT NOT NULL COMMENT '所属工作记忆轮次主键',
    request_id VARCHAR(128) NOT NULL COMMENT '写入该消息的轮次请求标识',
    origin_message_key VARCHAR(192) NOT NULL COMMENT '原始消息的稳定来源键，压缩重投影时用于追溯',
    run_id VARCHAR(64) NULL COMMENT '消息所属运行标识；压缩重投影时可为空',
    seq_no INT NOT NULL COMMENT '同一轮次内的消息顺序号，从 0 开始',
    role VARCHAR(16) NOT NULL COMMENT '模型消息角色：USER、ASSISTANT、TOOL 或 SYSTEM',
    content MEDIUMTEXT NULL COMMENT '下一次模型决策可见的消息正文',
    reasoning_content MEDIUMTEXT NULL COMMENT '预留的模型推理正文兼容字段，当前不写入',
    tool_call_id VARCHAR(128) NULL COMMENT 'TOOL 消息对应的 ASSISTANT 工具调用标识',
    tool_calls_json JSON NULL COMMENT 'ASSISTANT 发起的工具调用数组 JSON',
    base64_image MEDIUMTEXT NULL COMMENT '预留的图片消息兼容字段，当前不写入',
    message_kind VARCHAR(32) NOT NULL COMMENT '消息用途分类，如 query、assistant、tool_observation、system_note',
    visibility VARCHAR(32) NOT NULL DEFAULT 'ALL' COMMENT '消息可见范围，当前统一为 ALL',
    token_estimate INT NOT NULL DEFAULT 0 COMMENT '单条消息的粗略 token 估算值',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录更新时间',
    deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记：0 未删除，1 已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_wm_msg_turn_seq (turn_id, seq_no, deleted) COMMENT '约束轮次内消息顺序',
    KEY idx_wm_msg_session_seq (session_id, deleted, turn_id, seq_no) COMMENT '按会话顺序读取消息',
    KEY idx_wm_msg_session_scope (session_id, memory_scope, deleted, turn_id, seq_no) COMMENT '按作用域顺序读取消息',
    KEY idx_wm_msg_request (request_id, deleted, seq_no) COMMENT '按请求追溯消息',
    KEY idx_wm_msg_origin (session_id, origin_message_key, deleted) COMMENT '按来源键追溯压缩前后消息',
    KEY idx_wm_msg_visibility (session_id, visibility, deleted) COMMENT '按可见范围筛选消息',
    FULLTEXT KEY ft_wm_msg_content (content) WITH PARSER ngram COMMENT '为后续旧记忆全文查询预留'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话工作记忆消息；保存主 ReAct 可续接的原生角色、正文和工具调用';

CREATE TABLE IF NOT EXISTS ai_agent_working_memory_compaction (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '压缩审计记录主键',
    session_id VARCHAR(64) NOT NULL COMMENT '所属会话标识',
    trigger_request_id VARCHAR(64) NOT NULL COMMENT '触发本次压缩检查的 ReAct 请求标识',
    compact_request_id VARCHAR(128) NULL COMMENT '成功写入的新压缩轮次请求标识；失败时为空',
    strategy VARCHAR(32) NOT NULL COMMENT '实际压缩策略，如 micro-only、full-llm、local-fallback',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '压缩持久化状态：1 成功，2 失败',
    before_tokens INT NOT NULL DEFAULT 0 COMMENT '压缩前含 SYSTEM 与工具描述的 token 估算值',
    after_tokens INT NOT NULL DEFAULT 0 COMMENT '压缩后消息部分的 token 估算值',
    before_message_count INT NOT NULL DEFAULT 0 COMMENT '压缩前工作记忆消息数量',
    after_message_count INT NOT NULL DEFAULT 0 COMMENT '压缩后工作记忆消息数量',
    threshold_tokens INT NOT NULL DEFAULT 0 COMMENT '本次压缩使用的触发阈值',
    summary_text MEDIUMTEXT NULL COMMENT '摘要模型或本地降级生成的历史摘要',
    before_messages_json MEDIUMTEXT NULL COMMENT '压缩前消息的限长审计快照 JSON',
    after_messages_json MEDIUMTEXT NULL COMMENT '压缩后消息的限长审计快照 JSON',
    error_message VARCHAR(1024) NULL COMMENT '压缩或投影写入失败的截断错误信息',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录更新时间',
    deleted TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记：0 未删除，1 已删除',
    PRIMARY KEY (id),
    KEY idx_wm_compaction_session (session_id, deleted, id DESC) COMMENT '按会话查询压缩审计',
    KEY idx_wm_compaction_trigger (trigger_request_id, deleted) COMMENT '按触发请求查询压缩审计',
    KEY idx_wm_compaction_strategy (session_id, strategy, deleted) COMMENT '按会话及策略筛选压缩审计'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话工作记忆压缩审计；保留策略、前后统计和受限快照';

-- 回退时先关闭 memory.working.enabled，再回退应用代码。
-- 保留这三张追加式历史表，供后续 session_search 查询旧记忆。
