-- 仅用于已创建旧版 conversation_file_session、但缺少 active_file_count 的数据库；核对后执行一次。
ALTER TABLE conversation_file_session
    ADD COLUMN active_file_count INT NOT NULL DEFAULT 0 COMMENT '已预留且未移除的文件名额';

UPDATE conversation_file_session AS s
SET active_file_count = (
    SELECT COUNT(*) FROM conversation_file AS f
    WHERE f.session_id = s.session_id AND f.status <> 'REMOVED'
);
