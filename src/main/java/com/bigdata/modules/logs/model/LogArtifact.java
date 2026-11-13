package com.bigdata.modules.logs.model;

import java.time.LocalDateTime;

/**
 * 日志产物（文件）描述，用于 {@code /api/logs/artifacts} 列表。
 */
public class LogArtifact {

    private String name;
    private String path;
    private long size;
    private LocalDateTime modifiedAt;
    private boolean directory;

    public LogArtifact() {
    }

    public LogArtifact(String name, String path, long size, LocalDateTime modifiedAt, boolean directory) {
        this.name = name;
        this.path = path;
        this.size = size;
        this.modifiedAt = modifiedAt;
        this.directory = directory;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public LocalDateTime getModifiedAt() {
        return modifiedAt;
    }

    public void setModifiedAt(LocalDateTime modifiedAt) {
        this.modifiedAt = modifiedAt;
    }

    public boolean isDirectory() {
        return directory;
    }

    public void setDirectory(boolean directory) {
        this.directory = directory;
    }
}
