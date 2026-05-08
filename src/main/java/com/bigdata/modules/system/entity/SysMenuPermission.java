package com.bigdata.modules.system.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.bigdata.common.entity.BaseEntity;

import java.util.List;

@TableName("sys_menu_permission")
public class SysMenuPermission extends BaseEntity {

    private Long parentId;
    private String menuName;
    private String menuCode;
    private String path;
    private String component;
    private String icon;
    private Integer sortOrder;
    private Integer visible;
    private Integer status;

    @TableField(exist = false)
    private List<SysMenuPermission> children;

    public Long getParentId() {
        return parentId;
    }

    public void setParentId(Long parentId) {
        this.parentId = parentId;
    }

    public String getMenuName() {
        return menuName;
    }

    public void setMenuName(String menuName) {
        this.menuName = menuName;
    }

    public String getMenuCode() {
        return menuCode;
    }

    public void setMenuCode(String menuCode) {
        this.menuCode = menuCode;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getComponent() {
        return component;
    }

    public void setComponent(String component) {
        this.component = component;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Integer getVisible() {
        return visible;
    }

    public void setVisible(Integer visible) {
        this.visible = visible;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public List<SysMenuPermission> getChildren() {
        return children;
    }

    public void setChildren(List<SysMenuPermission> children) {
        this.children = children;
    }
}
