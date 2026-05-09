package com.bigdata.modules.development.service.execution;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.List;

public enum CodeType {

    SCALA("scala", "Scala", Arrays.asList("scala", "sc")),
    PYTHON("python", "Python", Arrays.asList("py")),
    SQL("sql", "SQL", Arrays.asList("sql")),
    JAVA("java", "Java", Arrays.asList("java")),
    SHELL("shell", "Shell", Arrays.asList("sh", "bash"));

    @EnumValue
    @JsonValue
    private final String code;

    private final String description;

    private final List<String> extensions;

    CodeType(String code, String description, List<String> extensions) {
        this.code = code;
        this.description = description;
        this.extensions = extensions;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getExtensions() {
        return extensions;
    }

    public static CodeType fromCode(String code) {
        return Arrays.stream(values())
                .filter(t -> t.code.equalsIgnoreCase(code))
                .findFirst()
                .orElse(null);
    }

    public static CodeType fromFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        String ext = fileName.substring(lastDot + 1).toLowerCase();
        return Arrays.stream(values())
                .filter(t -> t.extensions.contains(ext))
                .findFirst()
                .orElse(null);
    }
}
