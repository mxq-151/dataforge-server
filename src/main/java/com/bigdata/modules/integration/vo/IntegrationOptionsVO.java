package com.bigdata.modules.integration.vo;

import java.util.List;

/**
 * 数据集成页面所需的全部枚举选项与动态表单描述。
 */
public class IntegrationOptionsVO {

    private List<DataSourceTypeVO> sourceTypes;
    private List<DataSourceTypeVO> targetTypes;
    private List<OptionItemVO> clusterTypes;
    private List<OptionItemVO> statuses;
    private List<OptionItemVO> jobModes;
    private List<OptionItemVO> triggerTypes;

    public List<DataSourceTypeVO> getSourceTypes() {
        return sourceTypes;
    }

    public void setSourceTypes(List<DataSourceTypeVO> sourceTypes) {
        this.sourceTypes = sourceTypes;
    }

    public List<DataSourceTypeVO> getTargetTypes() {
        return targetTypes;
    }

    public void setTargetTypes(List<DataSourceTypeVO> targetTypes) {
        this.targetTypes = targetTypes;
    }

    public List<OptionItemVO> getClusterTypes() {
        return clusterTypes;
    }

    public void setClusterTypes(List<OptionItemVO> clusterTypes) {
        this.clusterTypes = clusterTypes;
    }

    public List<OptionItemVO> getStatuses() {
        return statuses;
    }

    public void setStatuses(List<OptionItemVO> statuses) {
        this.statuses = statuses;
    }

    public List<OptionItemVO> getJobModes() {
        return jobModes;
    }

    public void setJobModes(List<OptionItemVO> jobModes) {
        this.jobModes = jobModes;
    }

    public List<OptionItemVO> getTriggerTypes() {
        return triggerTypes;
    }

    public void setTriggerTypes(List<OptionItemVO> triggerTypes) {
        this.triggerTypes = triggerTypes;
    }
}
