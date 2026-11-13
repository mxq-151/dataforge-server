package com.bigdata.modules.logs.service;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 凭据引用解析。
 *
 * <p>支持四种写法，便于在环境变量、K8s Secret 挂载、Spring 配置之间切换而不改代码：</p>
 * <ul>
 *   <li>{@code env:DS_TOKEN} —— 环境变量</li>
 *   <li>{@code file:/run/secrets/ds_token} —— 文件内容（K8s Secret / Docker Secret）</li>
 *   <li>{@code config:scheduler.dolphinscheduler.token} —— 复用既有 Spring 配置</li>
 *   <li>{@code plain:xxx} 或裸值 —— 直接使用</li>
 * </ul>
 *
 * <p>解析失败抛 {@link IllegalStateException}，由日志源目录转成「未启用 + 原因」。</p>
 */
@Component
public class SecretResolver {

    private final Environment environment;

    public SecretResolver(Environment environment) {
        this.environment = environment;
    }

    public String resolve(String rawValue, String reference, String fieldName) {
        if (reference == null || reference.isBlank()) {
            return rawValue == null ? "" : rawValue.trim();
        }
        String ref = reference.trim();
        if (ref.startsWith("env:")) {
            String name = ref.substring("env:".length()).trim();
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                value = environment.getProperty(name);
            }
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(fieldName + " 引用的环境变量 " + name + " 未设置");
            }
            return value.trim();
        }
        if (ref.startsWith("file:")) {
            String path = ref.substring("file:".length()).trim();
            try {
                String value = Files.readString(Paths.get(path), StandardCharsets.UTF_8).trim();
                if (value.isEmpty()) {
                    throw new IllegalStateException(fieldName + " 引用的凭据文件为空: " + path);
                }
                return value;
            } catch (java.io.IOException e) {
                throw new IllegalStateException(fieldName + " 引用的凭据文件不可读: " + path);
            }
        }
        if (ref.startsWith("config:")) {
            String key = ref.substring("config:".length()).trim();
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(fieldName + " 引用的配置项为空: " + key);
            }
            return value.trim();
        }
        if (ref.startsWith("plain:")) {
            return ref.substring("plain:".length());
        }
        return ref;
    }
}
