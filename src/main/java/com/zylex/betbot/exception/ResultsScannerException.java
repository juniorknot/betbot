package com.zylex.betbot.exception;

public class ResultsScannerException extends OneXBetParserException {

    public ResultsScannerException() {
    }

    public ResultsScannerException(String message) {
        super(message);
    }

    public ResultsScannerException(String message, Throwable cause) {
        super(message, cause);
    }
}
