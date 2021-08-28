package com.alibaba.flink.ml.operator.util;

public class PythonRuntimeException extends RuntimeException {
    public PythonRuntimeException(String message) {
        super(message);
    }

    public PythonRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
