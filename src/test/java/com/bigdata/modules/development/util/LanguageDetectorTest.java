package com.bigdata.modules.development.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

class LanguageDetectorTest {

    @Test
    @DisplayName("检测Java语言")
    void testDetectJava() {
        assertEquals("java", LanguageDetector.detectFromFileName("Main.java"));
        assertEquals("java", LanguageDetector.detectFromExtension("java"));
    }

    @Test
    @DisplayName("检测Scala语言")
    void testDetectScala() {
        assertEquals("scala", LanguageDetector.detectFromFileName("App.scala"));
        assertEquals("scala", LanguageDetector.detectFromFileName("script.sc"));
        assertEquals("scala", LanguageDetector.detectFromExtension("scala"));
        assertEquals("scala", LanguageDetector.detectFromExtension("sc"));
    }

    @Test
    @DisplayName("检测Python语言")
    void testDetectPython() {
        assertEquals("python", LanguageDetector.detectFromFileName("main.py"));
        assertEquals("python", LanguageDetector.detectFromExtension("py"));
    }

    @Test
    @DisplayName("检测SQL语言")
    void testDetectSQL() {
        assertEquals("sql", LanguageDetector.detectFromFileName("query.sql"));
        assertEquals("sql", LanguageDetector.detectFromExtension("sql"));
    }

    @Test
    @DisplayName("检测Shell脚本")
    void testDetectShell() {
        assertEquals("shell", LanguageDetector.detectFromFileName("script.sh"));
        assertEquals("shell", LanguageDetector.detectFromFileName("build.bash"));
        assertEquals("shell", LanguageDetector.detectFromExtension("sh"));
        assertEquals("shell", LanguageDetector.detectFromExtension("bash"));
    }

    @Test
    @DisplayName("检测XML/HTML")
    void testDetectXmlHtml() {
        assertEquals("xml", LanguageDetector.detectFromFileName("config.xml"));
        assertEquals("xml", LanguageDetector.detectFromFileName("index.html"));
        assertEquals("xml", LanguageDetector.detectFromExtension("xml"));
        assertEquals("xml", LanguageDetector.detectFromExtension("html"));
    }

    @Test
    @DisplayName("检测JSON")
    void testDetectJson() {
        assertEquals("json", LanguageDetector.detectFromFileName("data.json"));
        assertEquals("json", LanguageDetector.detectFromExtension("json"));
    }

    @Test
    @DisplayName("检测YAML")
    void testDetectYaml() {
        assertEquals("yaml", LanguageDetector.detectFromFileName("config.yaml"));
        assertEquals("yaml", LanguageDetector.detectFromFileName("values.yml"));
        assertEquals("yaml", LanguageDetector.detectFromExtension("yaml"));
        assertEquals("yaml", LanguageDetector.detectFromExtension("yml"));
    }

    @Test
    @DisplayName("检测Markdown")
    void testDetectMarkdown() {
        assertEquals("markdown", LanguageDetector.detectFromFileName("README.md"));
        assertEquals("markdown", LanguageDetector.detectFromExtension("md"));
    }

    @Test
    @DisplayName("检测纯文本")
    void testDetectPlainText() {
        assertEquals("text", LanguageDetector.detectFromFileName("file.txt"));
        assertEquals("plaintext", LanguageDetector.detectFromFileName("README"));
        assertEquals("text", LanguageDetector.detectFromExtension("txt"));
        assertEquals("plaintext", LanguageDetector.detectFromExtension(""));
    }

    @Test
    @DisplayName("处理null和空字符串")
    void testNullAndEmpty() {
        assertEquals("plaintext", LanguageDetector.detectFromFileName(null));
        assertEquals("plaintext", LanguageDetector.detectFromFileName(""));
        assertEquals("plaintext", LanguageDetector.detectFromExtension(null));
        assertEquals("plaintext", LanguageDetector.detectFromExtension(""));
    }

    @Test
    @DisplayName("处理未知扩展名")
    void testUnknownExtension() {
        assertEquals("plaintext", LanguageDetector.detectFromFileName("file.xyz"));
        assertEquals("plaintext", LanguageDetector.detectFromExtension("xyz"));
        assertEquals("plaintext", LanguageDetector.detectFromExtension("unknown"));
    }
}
