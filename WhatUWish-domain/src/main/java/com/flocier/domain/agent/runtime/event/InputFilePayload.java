package com.flocier.domain.agent.runtime.event;

import lombok.Getter;

/** 本轮用户输入文件的安全展示字段；正文仍须通过受控文件会话 API 读取。 */
@Getter
public final class InputFilePayload implements RuntimeEventPayload {
    private final String fileId;
    private final String name;
    private final String mimeType;
    private final long size;
    private final int order;
    private final String status;

    public InputFilePayload(String fileId, String name, String mimeType, long size, int order, String status) {
        this.fileId = fileId;
        this.name = name;
        this.mimeType = mimeType;
        this.size = size;
        this.order = order;
        this.status = status;
    }
}
