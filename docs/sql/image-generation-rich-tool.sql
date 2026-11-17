-- Image Generation Rich Tool 专属执行投影与有序输入 Artifact 关联。
-- 部署包含本变更的应用前执行；输出图片继续以 artifact_ledger 为权威，不在本表复制 locator、URL 或图片字节。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS image_generation_execution (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    image_generation_execution_id VARCHAR(64) NOT NULL COMMENT '图片生成专属执行记录标识',
    execution_source VARCHAR(32) NOT NULL COMMENT '执行来源：AGENT_RUNTIME 或 WORKBENCH',
    tool_invocation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT 'Runtime 一对一关联的通用工具调用账本标识；工作台执行为空',
    python_execution_id VARCHAR(160) NULL COMMENT 'Python SSE 执行关联标识',
    run_id VARCHAR(64) NULL COMMENT '所属运行标识',
    session_id VARCHAR(64) NULL COMMENT '所属会话标识',
    request_id VARCHAR(128) NULL COMMENT '所属请求标识',
    generation_mode VARCHAR(32) NOT NULL COMMENT '生成模式：images 或 edits',
    requested_size VARCHAR(32) NULL COMMENT '请求的图片尺寸',
    requested_output_count INT NOT NULL DEFAULT 1 COMMENT '请求生成图片数量',
    generated_output_count INT NOT NULL DEFAULT 0 COMMENT '已登记的输出 Artifact 数量',
    reference_artifact_count INT NOT NULL DEFAULT 0 COMMENT '参考图片 Artifact 数量',
    mask_artifact_count INT NOT NULL DEFAULT 0 COMMENT '遮罩图片 Artifact 数量',
    used_fallback TINYINT NOT NULL DEFAULT 0 COMMENT '是否发生上游兼容接口降级',
    status VARCHAR(32) NOT NULL COMMENT '当前或终态执行状态',
    final_summary VARCHAR(1200) NULL COMMENT '受控终态摘要，不保存完整提示词',
    error_code VARCHAR(128) NULL COMMENT '受控执行错误码',
    error_message VARCHAR(1200) NULL COMMENT '受控执行错误信息',
    started_at DATETIME NOT NULL COMMENT '专属执行开始时间',
    finished_at DATETIME NULL COMMENT '专属执行终态时间',
    version_no BIGINT NOT NULL DEFAULT 0 COMMENT '乐观并发控制版本',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    update_time DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_image_generation_execution_id (image_generation_execution_id) COMMENT '图片生成专属执行记录唯一标识约束',
    UNIQUE KEY uk_image_generation_execution_tool_invocation (tool_invocation_id) COMMENT '每个工具调用仅有一个图片生成专属执行记录',
    KEY idx_image_generation_execution_run (run_id, started_at) COMMENT '按运行查询图片生成执行的索引',
    KEY idx_image_generation_execution_source_session_time (execution_source, session_id, started_at, image_generation_execution_id) COMMENT '按来源、会话和时间分页查询图片执行的索引',
    KEY idx_image_generation_execution_status_time (status, started_at) COMMENT '按状态和开始时间查询的索引',
    CONSTRAINT fk_image_generation_execution_tool_invocation FOREIGN KEY (tool_invocation_id)
        REFERENCES tool_invocation_ledger (tool_invocation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图片生成专属执行状态与终态投影';

CREATE TABLE IF NOT EXISTS image_generation_execution_input_artifact (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
    image_generation_execution_id VARCHAR(64) NOT NULL COMMENT '关联的图片生成专属执行记录标识',
    artifact_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '已授权输入 Artifact 标识',
    input_role VARCHAR(32) NOT NULL COMMENT '输入角色：reference 或 mask',
    input_order INT NOT NULL COMMENT '同一输入角色内稳定顺序，从零开始',
    create_time DATETIME NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_image_generation_input_role_order (image_generation_execution_id, input_role, input_order) COMMENT '同一执行下角色顺序唯一约束',
    UNIQUE KEY uk_image_generation_input_artifact_role (image_generation_execution_id, artifact_id, input_role) COMMENT '同一执行下同角色 Artifact 不重复关联',
    KEY idx_image_generation_input_artifact (artifact_id) COMMENT '按输入 Artifact 查询被引用图片执行的索引',
    CONSTRAINT fk_image_generation_input_execution FOREIGN KEY (image_generation_execution_id)
        REFERENCES image_generation_execution (image_generation_execution_id),
    CONSTRAINT fk_image_generation_input_artifact FOREIGN KEY (artifact_id)
        REFERENCES artifact_ledger (artifact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='图片生成执行与参考图遮罩图 Artifact 的有序关联';
