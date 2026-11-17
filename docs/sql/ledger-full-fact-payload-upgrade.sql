-- Ledger 完整事实载荷升级。
-- 执行前会检查当前数据库。已存在字段时不重复添加，旧记录保持 NULL，表示完整事实在历史上不可用。

SET @ledger_schema = DATABASE();

SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @ledger_schema
          AND table_name = 'llm_invocation_ledger'
          AND column_name = 'request_payload_json'
    ),
    'SELECT 1',
    'ALTER TABLE llm_invocation_ledger ADD COLUMN request_payload_json LONGTEXT NULL AFTER request_summary'
) INTO @ledger_sql;
PREPARE ledger_statement FROM @ledger_sql;
EXECUTE ledger_statement;
DEALLOCATE PREPARE ledger_statement;

-- 通过 UTF-8 字节构造中文注释，避免 Windows 终端管道执行时发生编码替换。
SET @ledger_comment_text = CONVERT(0x4C4C4D20E5AE8CE695B4E8AFB7E6B182E4BA8BE5AE9E204A534F4EEFBC88E99D9EE688AAE696ADEFBC8CE6958FE6849FE8BF90E8A18CE697B6E5AD97E6AEB5E5B7B2E884B1E6958FEFBC89 USING utf8mb4);
SET @ledger_sql = CONCAT('ALTER TABLE llm_invocation_ledger MODIFY COLUMN request_payload_json LONGTEXT NULL COMMENT ', QUOTE(@ledger_comment_text));
PREPARE ledger_statement FROM @ledger_sql;
EXECUTE ledger_statement;
DEALLOCATE PREPARE ledger_statement;

SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @ledger_schema
          AND table_name = 'llm_invocation_ledger'
          AND column_name = 'response_payload_json'
    ),
    'SELECT 1',
    'ALTER TABLE llm_invocation_ledger ADD COLUMN response_payload_json LONGTEXT NULL AFTER response_summary'
) INTO @ledger_sql;
PREPARE ledger_statement FROM @ledger_sql;
EXECUTE ledger_statement;
DEALLOCATE PREPARE ledger_statement;

SET @ledger_comment_text = CONVERT(0x4C4C4D20E5AE8CE695B4E5938DE5BA94E4BA8BE5AE9E204A534F4EEFBC88E99D9EE688AAE696ADEFBC8CE6958FE6849FE8BF90E8A18CE697B6E5AD97E6AEB5E5B7B2E884B1E6958FEFBC89 USING utf8mb4);
SET @ledger_sql = CONCAT('ALTER TABLE llm_invocation_ledger MODIFY COLUMN response_payload_json LONGTEXT NULL COMMENT ', QUOTE(@ledger_comment_text));
PREPARE ledger_statement FROM @ledger_sql;
EXECUTE ledger_statement;
DEALLOCATE PREPARE ledger_statement;

SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @ledger_schema
          AND table_name = 'tool_invocation_ledger'
          AND column_name = 'request_payload_json'
    ),
    'SELECT 1',
    'ALTER TABLE tool_invocation_ledger ADD COLUMN request_payload_json LONGTEXT NULL AFTER request_summary'
) INTO @ledger_sql;
PREPARE ledger_statement FROM @ledger_sql;
EXECUTE ledger_statement;
DEALLOCATE PREPARE ledger_statement;

SET @ledger_comment_text = CONVERT(0xE5B7A5E585B7E5AE8CE695B4E8AFB7E6B182E4BA8BE5AE9E204A534F4EEFBC88E99D9EE688AAE696ADEFBC8CE6958FE6849FE8BF90E8A18CE697B6E5AD97E6AEB5E5B7B2E884B1E6958FEFBC89 USING utf8mb4);
SET @ledger_sql = CONCAT('ALTER TABLE tool_invocation_ledger MODIFY COLUMN request_payload_json LONGTEXT NULL COMMENT ', QUOTE(@ledger_comment_text));
PREPARE ledger_statement FROM @ledger_sql;
EXECUTE ledger_statement;
DEALLOCATE PREPARE ledger_statement;

SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @ledger_schema
          AND table_name = 'tool_invocation_ledger'
          AND column_name = 'response_payload_json'
    ),
    'SELECT 1',
    'ALTER TABLE tool_invocation_ledger ADD COLUMN response_payload_json LONGTEXT NULL AFTER response_summary'
) INTO @ledger_sql;
PREPARE ledger_statement FROM @ledger_sql;
EXECUTE ledger_statement;
DEALLOCATE PREPARE ledger_statement;

SET @ledger_comment_text = CONVERT(0xE5B7A5E585B7E5AE8CE695B4E5938DE5BA94E4BA8BE5AE9E204A534F4EEFBC88E99D9EE688AAE696ADEFBC8CE6958FE6849FE8BF90E8A18CE697B6E5AD97E6AEB5E5B7B2E884B1E6958FEFBC89 USING utf8mb4);
SET @ledger_sql = CONCAT('ALTER TABLE tool_invocation_ledger MODIFY COLUMN response_payload_json LONGTEXT NULL COMMENT ', QUOTE(@ledger_comment_text));
PREPARE ledger_statement FROM @ledger_sql;
EXECUTE ledger_statement;
DEALLOCATE PREPARE ledger_statement;
