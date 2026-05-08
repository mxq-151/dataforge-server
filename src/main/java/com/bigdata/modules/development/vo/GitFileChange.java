package com.bigdata.modules.development.vo;

public record GitFileChange(
        ChangeType changeType,
        String oldPath,
        String newPath
) {
    public enum ChangeType {
        ADD, MODIFY, DELETE, RENAME, COPY
    }
}
