package com.flocier.application.agent.imagegeneration.workbench;

import lombok.Getter;

/** 图片工作台向 HTTP 层传递的受控业务失败。 */
@Getter
public class WorkbenchImageGenerationException extends RuntimeException {
    private final String errorCode;

    public WorkbenchImageGenerationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
