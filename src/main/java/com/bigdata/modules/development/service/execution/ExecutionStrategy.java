package com.bigdata.modules.development.service.execution;

public interface ExecutionStrategy {

    CodeExecutionResult execute(CodeExecutionContext context);

    /**
     * 在异步执行之前检查权限（在请求线程中执行，SecurityContext可用）
     * 默认不做任何检查，JDBC类策略可覆写此方法
     */
    default void checkPermission(CodeExecutionContext context) {
        // 默认无权限检查
    }

    EngineType getEngineType();

    boolean supports(CodeType codeType);
}
