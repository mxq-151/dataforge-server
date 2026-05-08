package com.bigdata.modules.development.vo;

public record GitAuthor(String name, String email) {
    public static final GitAuthor SYSTEM = new GitAuthor("System", "system@bigdata-platform.com");
}
