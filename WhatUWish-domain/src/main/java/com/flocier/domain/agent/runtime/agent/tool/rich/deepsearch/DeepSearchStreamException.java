package com.flocier.domain.agent.runtime.agent.tool.rich.deepsearch;

/** DeepSearch HTTP / SSE 协议无法形成可信最终结果时抛出的受控异常。 */
public class DeepSearchStreamException extends RuntimeException {

    private final String errorCode;

    public DeepSearchStreamException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public DeepSearchStreamException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
