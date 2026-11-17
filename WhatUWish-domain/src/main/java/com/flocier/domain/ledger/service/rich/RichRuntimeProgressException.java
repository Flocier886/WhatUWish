package com.flocier.domain.ledger.service.rich;

import lombok.Getter;

@Getter
public class RichRuntimeProgressException extends RuntimeException {
    private final String errorCode;

    public RichRuntimeProgressException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
