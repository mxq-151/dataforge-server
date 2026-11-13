package com.bigdata.modules.logs.adapter;

/**
 * 适配器层异常：只表达「这次取日志失败的原因」，由查询编排统一转成降级结果或明确错误。
 */
public class LogAdapterException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Code {
        /** 调用参数不合法（缺 ref、游标不支持等） */
        BAD_REQUEST,
        /** 上游明确表示对象不存在（如 DS task instance not found） */
        NOT_FOUND,
        /** 上游不可达 / 认证失败 / 5xx */
        UNAVAILABLE,
        /** 超时 */
        TIMEOUT,
        /** 上游不具备该能力（如 SHS 无日志接口、适配器不支持产物） */
        UNSUPPORTED,
        /** 其他失败 */
        FAILED
    }

    private final Code code;

    public LogAdapterException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public LogAdapterException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
