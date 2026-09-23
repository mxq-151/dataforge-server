package com.bigdata.modules.asset.vo;

import java.io.Serializable;

/**
 * 该表关联的指标（基于 indicators_fields 反查）。
 *
 * <p>字段名与前端 src/api/assetCatalog.ts 的契约逐字对应，改动需两端同步。</p>
 *
 * @author dataforge
 */
public class AssetIndicatorVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 指标 id */
    private Long id;

    /** 指标编码 */
    private String code;

    /** 指标名称 */
    private String name;

    /** 主题域 */
    private String topic;

    /** 业务负责人 */
    private String businessOwner;

    /** 该指标在此表上引用的字段名 */
    private String fieldName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getBusinessOwner() { return businessOwner; }
    public void setBusinessOwner(String businessOwner) { this.businessOwner = businessOwner; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }
}
