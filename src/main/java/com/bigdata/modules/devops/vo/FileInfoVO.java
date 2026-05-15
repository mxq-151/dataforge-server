package com.bigdata.modules.devops.vo;

import java.io.Serializable;
import java.util.Objects;

public class FileInfoVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;
    private Boolean isDir;
    private Long size;
    private String modifiedTime;
    private String permissions;
    private String path;
    private Integer replication;
    private String owner;
    private String group;

    public FileInfoVO() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getIsDir() {
        return isDir;
    }

    public void setIsDir(Boolean isDir) {
        this.isDir = isDir;
    }

    public Long getSize() {
        return size;
    }

    public void setSize(Long size) {
        this.size = size;
    }

    public String getModifiedTime() {
        return modifiedTime;
    }

    public void setModifiedTime(String modifiedTime) {
        this.modifiedTime = modifiedTime;
    }

    public String getPermissions() {
        return permissions;
    }

    public void setPermissions(String permissions) {
        this.permissions = permissions;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Integer getReplication() {
        return replication;
    }

    public void setReplication(Integer replication) {
        this.replication = replication;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = group;
    }

    public static FileInfoVO fromMap(java.util.Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        FileInfoVO vo = new FileInfoVO();
        vo.setName(map.get("name") != null ? map.get("name").toString() : null);
        vo.setIsDir(map.get("isDir") != null ? Boolean.valueOf(map.get("isDir").toString()) : false);
        vo.setSize(map.get("size") != null ? ((Number) map.get("size")).longValue() : 0L);
        vo.setModifiedTime(map.get("modifiedTime") != null ? map.get("modifiedTime").toString() : null);
        vo.setPermissions(map.get("permissions") != null ? map.get("permissions").toString() : null);
        vo.setPath(map.get("path") != null ? map.get("path").toString() : null);

        if (map.containsKey("replication") && map.get("replication") != null) {
            vo.setReplication(((Number) map.get("replication")).intValue());
        }
        if (map.containsKey("owner")) {
            vo.setOwner(map.get("owner") != null ? map.get("owner").toString() : null);
        }
        if (map.containsKey("group")) {
            vo.setGroup(map.get("group") != null ? map.get("group").toString() : null);
        }
        return vo;
    }
}
