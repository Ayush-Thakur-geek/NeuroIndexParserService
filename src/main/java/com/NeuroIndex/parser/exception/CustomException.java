package com.NeuroIndex.parser.exception;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CustomException extends RuntimeException {

    private final String code;
    private final int status;

    public CustomException(
            String message,
            String code,
            int status,
            Throwable cause
    ) {
        super(message, cause);

        this.code = code;
        this.status = status;
    }
}
