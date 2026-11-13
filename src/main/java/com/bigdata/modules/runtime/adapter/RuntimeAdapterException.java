package com.bigdata.modules.runtime.adapter;

/**
 * 运行时适配器异常：只表达「这次取指标失败的原因」，由编排层转成降级结果或明确错误。
 */
public class RuntimeAdapterException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        BAD_REQUEST,
        NOT_FOUND,
        UNAVAILABLE,
        TIMEOUT,
        UNSUPPORTED,
        FAILED
    }

    private final Code code;

    public RuntimeAdapterException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public RuntimeAdapterException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
