package com.bigdata.modules.telemetry.config.properties;

public class QueryOptions {
    /** VM 专用：是否允许返回部分结果；默认 false，避免把部分结果当完整。 */
    private boolean partialResponse = false;
    private int maxPointsPerTimeseries = 11000;

    public boolean isPartialResponse() {
        return partialResponse;
    }

    public void setPartialResponse(boolean partialResponse) {
        this.partialResponse = partialResponse;
    }

    public int getMaxPointsPerTimeseries() {
        return maxPointsPerTimeseries;
    }

    public void setMaxPointsPerTimeseries(int maxPointsPerTimeseries) {
        this.maxPointsPerTimeseries = maxPointsPerTimeseries;
    }
}
