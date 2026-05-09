package com.bigdata.modules.development.service.execution;

public interface ExecutionStrategy {

    CodeExecutionResult execute(CodeExecutionContext context);

    EngineType getEngineType();

    boolean supports(CodeType codeType);
}
