package com.flocier.domain.agent.runtime.agent.tool.rich.imagegeneration;

/** 图片生成私有协议、输入授权或上游传输的受控失败。 */
public class ImageGenerationStreamException extends RuntimeException {

    private final String errorCode;

    public ImageGenerationStreamException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ImageGenerationStreamException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
