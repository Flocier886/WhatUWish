package com.flocier.application.agent.file;

/** 文件会话业务错误，code 由 HTTP 边界映射为稳定的拒绝或不可用状态。 */
public class ConversationFileAccessException extends RuntimeException {
    private final String code;

    public ConversationFileAccessException(String code) {
        super(code);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
