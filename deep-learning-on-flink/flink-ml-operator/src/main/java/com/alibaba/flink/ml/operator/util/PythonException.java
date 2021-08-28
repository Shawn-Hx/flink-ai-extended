package com.alibaba.flink.ml.operator.util;

public class PythonException extends Exception {
    public PythonException(String message) {
        super(message);
    }

    public PythonException(String message, Throwable cause) {
        super(message, cause);
    }
}
