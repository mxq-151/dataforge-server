package com.bigdata.modules.development.vo;

public record GitFileInfo(
        String name,
        String path,
        String type,
        String language,
        long fileSize
) {
    public boolean isFolder() {
        return "folder".equals(type);
    }

    public boolean isFile() {
        return "file".equals(type);
    }
}
