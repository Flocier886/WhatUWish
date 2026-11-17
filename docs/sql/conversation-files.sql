-- 用户文件在 Run 创建前保存；会话文件身份和每轮 Artifact 输入事实分离。
CREATE TABLE IF NOT EXISTS conversation_file_session (
    session_id VARCHAR(128) NOT NULL COMMENT '服务端签发的文件会话 ID',
    token_hash CHAR(64) NOT NULL COMMENT '旧版令牌字段，当前单用户运行时仅写兼容占位值',
    active_file_count INT NOT NULL DEFAULT 0 COMMENT '已预留且未移除的文件名额',
    created_at DATETIME(3) NOT NULL COMMENT '文件会话创建时间',
    expires_at DATETIME(3) NOT NULL COMMENT '旧版令牌有效期字段，当前不参与访问判断',
    PRIMARY KEY (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='会话文件身份与名额记录';

CREATE TABLE IF NOT EXISTS conversation_file (
    file_id VARCHAR(128) NOT NULL COMMENT '不可变文件身份，可跨 Run 复用',
    session_id VARCHAR(128) NOT NULL COMMENT '所属文件会话 ID，上传时无需预先存在 Run',
    display_name VARCHAR(255) NOT NULL COMMENT '净化后的用户文件展示名称，同名文件允许并存',
    mime_type VARCHAR(128) NOT NULL COMMENT '经服务端内容校验后的文件 MIME 类型',
    file_size BIGINT NOT NULL COMMENT '上传文件原始字节数',
    file_hash CHAR(64) NOT NULL COMMENT '原始字节 SHA-256 摘要，用于工作区物化校验',
    storage_type VARCHAR(64) NOT NULL COMMENT '服务端内容存储类型，不向浏览器公开',
    storage_key VARCHAR(512) NOT NULL COMMENT '服务端内容对象键，不向浏览器公开',
    status VARCHAR(24) NOT NULL COMMENT 'READY、UNAVAILABLE、REMOVED',
    created_at DATETIME(3) NOT NULL COMMENT '文件上传登记时间',
    removed_at DATETIME(3) NULL COMMENT '软移除时间；历史 Run 输入事实仍保留',
    PRIMARY KEY (file_id),
    KEY idx_conversation_file_session (session_id, created_at, file_id),
    CONSTRAINT fk_conversation_file_session FOREIGN KEY (session_id)
        REFERENCES conversation_file_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户上传的不可变会话文件';
