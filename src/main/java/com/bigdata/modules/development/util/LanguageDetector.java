package com.bigdata.modules.development.util;

import java.util.Map;

public final class LanguageDetector {

    private static final Map<String, String> EXTENSION_TO_LANGUAGE = Map.ofEntries(
            Map.entry("java", "java"),
            Map.entry("scala", "scala"),
            Map.entry("sc", "scala"),
            Map.entry("py", "python"),
            Map.entry("sql", "sql"),
            Map.entry("sh", "shell"),
            Map.entry("bash", "shell"),
            Map.entry("xml", "xml"),
            Map.entry("html", "xml"),
            Map.entry("htm", "xml"),
            Map.entry("json", "json"),
            Map.entry("yaml", "yaml"),
            Map.entry("yml", "yaml"),
            Map.entry("properties", "properties"),
            Map.entry("conf", "properties"),
            Map.entry("ini", "properties"),
            Map.entry("md", "markdown"),
            Map.entry("markdown", "markdown"),
            Map.entry("txt", "text")
    );

    private LanguageDetector() {
    }

    public static String detectFromFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "plaintext";
        }

        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex <= 0) {
            return "plaintext";
        }

        String extension = fileName.substring(lastDotIndex + 1).toLowerCase();
        return EXTENSION_TO_LANGUAGE.getOrDefault(extension, "plaintext");
    }

    public static String detectFromExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return "plaintext";
        }
        return EXTENSION_TO_LANGUAGE.getOrDefault(extension.toLowerCase(), "plaintext");
    }
}
