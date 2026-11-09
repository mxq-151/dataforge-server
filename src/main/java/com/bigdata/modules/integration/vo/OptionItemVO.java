package com.bigdata.modules.integration.vo;

/**
 * 下拉选项（编码 + 展示名）。
 */
public class OptionItemVO {

    private String code;
    private String label;

    public OptionItemVO() {
    }

    public OptionItemVO(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
