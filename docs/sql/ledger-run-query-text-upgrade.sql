-- Promote the original user input of each Run to a first-class ledger field.
-- This migration is idempotent. It never guesses data from a Session or an LLM prompt.

SET @ledger_schema = DATABASE();

SELECT IF(
    EXISTS(
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = @ledger_schema
          AND table_name = 'run_ledger'
          AND column_name = 'query_text'
    ),
    'SELECT 1',
    'ALTER TABLE run_ledger ADD COLUMN query_text LONGTEXT NULL COMMENT ''Original user input for this run'' AFTER summary'
) INTO @ledger_sql;
PREPARE ledger_statement FROM @ledger_sql;
EXECUTE ledger_statement;
DEALLOCATE PREPARE ledger_statement;

-- Only promote an already persisted, per-Run legacy fact. NULL remains NULL when it was never recorded.
UPDATE run_ledger
SET query_text = JSON_UNQUOTE(JSON_EXTRACT(ext_json, '$.queryText'))
WHERE (query_text IS NULL OR TRIM(query_text) = '')
  AND ext_json IS NOT NULL
  AND JSON_VALID(ext_json)
  AND JSON_TYPE(JSON_EXTRACT(ext_json, '$.queryText')) = 'STRING'
  AND TRIM(JSON_UNQUOTE(JSON_EXTRACT(ext_json, '$.queryText'))) <> '';
