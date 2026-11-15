package com.bigdata.modules.telemetry.model;

/**
 * 类型化能力/错误码，替代用一段中文让调用方猜含义。
 *
 * <p>{@code retryable} 表达“重试是否有意义”：配置缺失、权限不足、未实现都不可重试。</p>
 */
public enum CapabilityCode {

    OK(true),
    NOT_CONFIGURED(false),
    SOURCE_DISABLED(false),
    NO_PROVIDER(false),
    DATA_TYPE_NOT_IMPLEMENTED(false),
    HOST_NOT_MANAGED(false),
    VERSION_UNSUPPORTED(false),
    PERMISSION_DENIED(false),
    DEPENDENCY_MISSING(false),
    UPSTREAM_TIMEOUT(true),
    UPSTREAM_ERROR(true),
    IO_ERROR(true),
    UPSTREAM_UNAUTHORIZED(false),
    PARSE_ERROR(false);

    private final boolean retryable;

    CapabilityCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
