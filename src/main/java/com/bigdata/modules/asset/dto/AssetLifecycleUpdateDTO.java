package com.bigdata.modules.asset.dto;

import java.io.Serializable;

/**
 * 生命周期修改入参（热 / 冷 / 保留天数，单位：天）。
 *
 * <p>字段名与前端 {@code AssetLifecycleVO} 逐字对应；三个字段均为必填，
 * 服务端校验 {@code hotDays ≤ coldDays ≤ keepDays}。</p>
 *
 * @author dataforge
 */
public class AssetLifecycleUpdateDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 热数据天数 */
    private Integer hotDays;

    /** 冷数据天数 */
    private Integer coldDays;

    /** 数据保留天数 */
    private Integer keepDays;

    public Integer getHotDays() { return hotDays; }
    public void setHotDays(Integer hotDays) { this.hotDays = hotDays; }
    public Integer getColdDays() { return coldDays; }
    public void setColdDays(Integer coldDays) { this.coldDays = coldDays; }
    public Integer getKeepDays() { return keepDays; }
    public void setKeepDays(Integer keepDays) { this.keepDays = keepDays; }
}
