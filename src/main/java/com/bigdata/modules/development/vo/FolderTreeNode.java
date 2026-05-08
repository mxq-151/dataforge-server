package com.bigdata.modules.development.vo;

import java.util.List;

public record FolderTreeNode(
        String name,
        String path,
        List<FolderTreeNode> children
) {
    public static FolderTreeNode of(String name, String path) {
        return new FolderTreeNode(name, path, java.util.Collections.emptyList());
    }

    public static FolderTreeNode of(String name, String path, List<FolderTreeNode> children) {
        return new FolderTreeNode(name, path, children != null ? children : java.util.Collections.emptyList());
    }
}