package com.bigdata.modules.telemetry.model;

/**
 * 数据类型的实现状态。
 *
 * <p>{@link #DECLARED_NOT_IMPLEMENTED} 的类型只能返回 {@code NOT_IMPLEMENTED}，
 * 不允许用 0 值、空数组或估算值冒充数据。</p>
 */
public enum DataTypeStatus {
    IMPLEMENTED,
    DECLARED_NOT_IMPLEMENTED
}
