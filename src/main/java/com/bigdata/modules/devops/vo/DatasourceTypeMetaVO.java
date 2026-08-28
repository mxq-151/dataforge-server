package com.bigdata.modules.devops.vo;

import com.bigdata.modules.development.service.execution.CodeType;
import com.bigdata.modules.devops.enums.DatasourceType;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 数据源类型的富结构视图，用于前端区分「计算 / 存储」能力与「支持的语言」。
 * <p>
 * {@link DatasourceType} 枚举本身因 {@code @JsonValue} 在 {@code code} 上，
 * 直接序列化时只会输出 code 字符串，导致 {@code exec}/{@code store}/{@code language}
 * 三个合并后新增的属性对前端不可见。该 VO 显式暴露这些元数据，
 * 供 {@code GET /devops/datasource/types} 使用。
 * </p>
 */
public class DatasourceTypeMetaVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 类型编码，如 spark / doris */
    private String code;

    /** 类型描述，如 Apache Doris */
    private String description;

    /** 是否具备计算（执行）能力 */
    private boolean exec;

    /** 是否具备存储能力 */
    private boolean store;

    /** 支持的代码类型编码列表，如 [sql] / [java, scala, python, sql] */
    private List<String> language;

    public static DatasourceTypeMetaVO from(DatasourceType type) {
        DatasourceTypeMetaVO vo = new DatasourceTypeMetaVO();
        vo.setCode(type.getCode());
        vo.setDescription(type.getDescription());
        vo.setExec(type.isExec());
        vo.setStore(type.isStore());
        vo.setLanguage(type.getLanguage() == null ? null :
                type.getLanguage().stream().map(CodeType::getCode).collect(Collectors.toList()));
        return vo;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isExec() {
        return exec;
    }

    public void setExec(boolean exec) {
        this.exec = exec;
    }

    public boolean isStore() {
        return store;
    }

    public void setStore(boolean store) {
        this.store = store;
    }

    public List<String> getLanguage() {
        return language;
    }

    public void setLanguage(List<String> language) {
        this.language = language;
    }
}
