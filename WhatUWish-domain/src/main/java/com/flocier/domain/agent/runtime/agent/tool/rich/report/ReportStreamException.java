package com.flocier.domain.agent.runtime.agent.tool.rich.report;

/** Report 跨语言协议、输入准入或传输失败的受控异常。 */
public class ReportStreamException extends RuntimeException {
    private final String errorCode;

    public ReportStreamException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ReportStreamException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
