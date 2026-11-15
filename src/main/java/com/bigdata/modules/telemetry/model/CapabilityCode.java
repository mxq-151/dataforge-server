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
    PARSE_ERROR(false),

    // ---- v2 按存储后端取数新增 ----
    INVALID_ARGUMENT(false),
    HOST_REQUIRED(false),
    QUERY_TOO_EXPENSIVE(false),
    QUERY_MODE_UNSUPPORTED(false),
    ES_MAPPING_INCOMPATIBLE(false),
    CURSOR_EXPIRED(false),
    CURSOR_MISMATCH(false),
    TIME_FILTER_UNSUPPORTED(false),
    STREAM_NOT_SEPARABLE(false),
    NOT_EQUIVALENT(false),
    BACKEND_UNAVAILABLE(true),
    RATE_LIMITED(true),
    PARTIAL_RESPONSE(true),
    NOT_FOUND(false),

    // ---- log.service / log.task 分类隔离新增 ----
    /** 后端不属于当前 dataType 允许的集合（如用 yarn 查服务日志）。 */
    LOG_DATA_TYPE_MISMATCH(false),
    /** 该 stream 在当前 dataType 下不受支持（如 log.task 查 slowquery）。 */
    STREAM_NOT_SUPPORTED(false),
    /** jobName 命中多条任务实例且未给消歧条件：禁止静默取第一条。 */
    AMBIGUOUS_REFERENCE(false),
    /** 任务实例存在但日志为空：按不可用返回，不伪造空数组成功。 */
    NO_LOG_CONTENT(false),
    /** 日志源全部不可取（如 YARN 聚合未完成且 NM 本地目录未挂载）：带 reason 返回，不用空串兜底。 */
    LOG_SOURCE_UNAVAILABLE(false),
    /** 结果触达条数/字节上限被截断：数据可用但不完整，必须显式标记。 */
    RESULT_TRUNCATED(false);

    private final boolean retryable;

    CapabilityCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
