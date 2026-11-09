package com.bigdata.modules.integration.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * 任务参数动态更新入参：只改参数，不重建任务记录；下次提交时生效。
 *
 * <p>读写并行度与附加参数都在任务级，不存在逐表覆盖。</p>
 */
public class IntegrationParamsDTO {

    @NotNull(message = "附加参数不能为空")
    private Map<String, Object> extraParams;

    private Integer readParallelism;

    private Integer writeParallelism;

    public Map<String, Object> getExtraParams() {
        return extraParams;
    }

    public void setExtraParams(Map<String, Object> extraParams) {
        this.extraParams = extraParams;
    }

    public Integer getReadParallelism() {
        return readParallelism;
    }

    public void setReadParallelism(Integer readParallelism) {
        this.readParallelism = readParallelism;
    }

    public Integer getWriteParallelism() {
        return writeParallelism;
    }

    public void setWriteParallelism(Integer writeParallelism) {
        this.writeParallelism = writeParallelism;
    }
}
