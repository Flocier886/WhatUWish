-- Tool 与父 LLM 调用关联升级。
-- 该脚本可重复执行；历史工具调用保持 llm_invocation_id 为 NULL，表示父调用不可确认，不做危险回填。
SET @ledger_schema = DATABASE();

SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @ledger_schema
          AND table_name = 'tool_invocation_ledger'
          AND column_name = 'llm_invocation_id'
    ),
    'SELECT 1',
    'ALTER TABLE tool_invocation_ledger ADD COLUMN llm_invocation_id VARCHAR(64) NULL COMMENT ''父 LLM 调用账本标识；非 LLM 触发或历史记录无法确认时为空'' AFTER tool_call_id'
) INTO @ledger_sql;
PREPARE ledger_statement FROM @ledger_sql;
EXECUTE ledger_statement;
DEALLOCATE PREPARE ledger_statement;

SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @ledger_schema
          AND table_name = 'tool_invocation_ledger'
          AND column_name = 'dispatch_order'
    ),
    'SELECT 1',
    'ALTER TABLE tool_invocation_ledger ADD COLUMN dispatch_order INT NULL COMMENT ''同一父 LLM 返回多个工具调用时的稳定分发顺序，从 0 开始'' AFTER llm_invocation_id'
) INTO @ledger_sql;
PREPARE ledger_statement FROM @ledger_sql;
EXECUTE ledger_statement;
DEALLOCATE PREPARE ledger_statement;

SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = @ledger_schema
          AND table_name = 'tool_invocation_ledger'
          AND index_name = 'idx_tool_invocation_llm_dispatch'
    ),
    'SELECT 1',
    'CREATE INDEX idx_tool_invocation_llm_dispatch ON tool_invocation_ledger (llm_invocation_id, dispatch_order)'
) INTO @ledger_sql;
PREPARE ledger_statement FROM @ledger_sql;
EXECUTE ledger_statement;
DEALLOCATE PREPARE ledger_statement;
