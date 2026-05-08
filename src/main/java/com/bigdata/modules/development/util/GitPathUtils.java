package com.bigdata.modules.development.util;

public final class GitPathUtils {

    private GitPathUtils() {
    }

    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }

        String normalized = path.trim();

        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }

        return normalized;
    }

    public static String toGitPath(String path) {
        return normalizePath(path);
    }

    public static String toRelativePath(String parentPath, String name) {
        String normalizedParent = normalizePath(parentPath);

        if (normalizedParent.isEmpty()) {
            return name;
        }

        return normalizedParent + "/" + name;
    }

    public static boolean isRootPath(String path) {
        return path == null || path.isEmpty() || "/".equals(path);
    }
}
