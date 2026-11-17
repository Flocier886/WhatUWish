-- 图片工作台与 Agent Runtime 共用图片生成执行投影的兼容迁移。
-- 前置条件：已执行 image-generation-rich-tool.sql，且 image_generation_execution 已存在。
-- 本文件必须以 UTF-8 在 IDEA Database Console 执行；命令行导入时使用 --default-character-set=utf8mb4。
-- 先声明连接字符集，避免表、字段和索引中文注释以错误编码写入 MySQL 元数据。
SET NAMES utf8mb4;

ALTER TABLE image_generation_execution
    ADD COLUMN execution_source VARCHAR(32) NULL COMMENT '执行来源：AGENT_RUNTIME 或 WORKBENCH' AFTER image_generation_execution_id;

UPDATE image_generation_execution
SET execution_source = 'AGENT_RUNTIME'
WHERE execution_source IS NULL OR execution_source = '';

ALTER TABLE image_generation_execution
    MODIFY COLUMN execution_source VARCHAR(32) NOT NULL COMMENT '执行来源：AGENT_RUNTIME 或 WORKBENCH',
    MODIFY COLUMN tool_invocation_id VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT 'Runtime 一对一关联的通用工具调用账本标识；工作台执行为空';

ALTER TABLE image_generation_execution
    ADD KEY idx_image_generation_execution_source_session_time (execution_source, session_id, started_at, image_generation_execution_id)
        COMMENT '按来源、会话和时间分页查询图片执行的索引';

-- 显式重写关键中文元数据注释，用于修复此前由错误客户端编码写入的乱码。
-- 不修改主键列的字符集，避免破坏被 input Artifact 表外键引用的兼容性。
ALTER TABLE image_generation_execution
    COMMENT = '图片生成执行状态与终态投影';

ALTER TABLE image_generation_execution
    MODIFY COLUMN image_generation_execution_id
        VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci
        NOT NULL COMMENT '图片生成专属执行记录标识';

-- `uk_image_generation_execution_tool_invocation` 与 FK 继续保留：MySQL 对 UNIQUE 的 NULL 值允许多行，
-- 因此 Runtime 仍是一对一关系，工作台无需伪造 Tool Invocation。
