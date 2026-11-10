package com.bigdata.modules.integration.service.submit;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Flink 版本号解析与比较。
 *
 * <p>数据集成生成的作业依赖 Flink 2.x 的原生作业血缘接口（FLIP-314：
 * {@code execution.job-status-changed-listeners}），因此要求 Flink 2.2.0 及以上。
 * 版本直接取自安装目录 {@code lib/flink-dist-*.jar} 的文件名，不额外启动进程。</p>
 */
final class FlinkVersion implements Comparable<FlinkVersion> {

    /** 通用版本号：x.y[.z] */
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private final int major;
    private final int minor;
    private final int patch;

    private FlinkVersion(int major, int minor, int patch) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    /** 解析版本号；文本中没有 x.y[.z] 时返回 null */
    static FlinkVersion parse(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = VERSION.matcher(text);
        return matcher.find() ? fromMatcher(matcher) : null;
    }

    private static FlinkVersion fromMatcher(Matcher matcher) {
        return new FlinkVersion(Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3)));
    }

    /** 从 Flink 安装目录的 lib/flink-dist-*.jar 读取版本；目录不存在或没有 dist 包时返回 null */
    static FlinkVersion detect(Path flinkHome) {
        if (flinkHome == null) {
            return null;
        }
        Path lib = flinkHome.resolve("lib");
        if (!Files.isDirectory(lib)) {
            return null;
        }
        FlinkVersion latest = null;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(lib, "flink-dist*.jar")) {
            for (Path jar : stream) {
                FlinkVersion version = fromDistJar(jar.getFileName().toString());
                if (version != null && (latest == null || version.compareTo(latest) > 0)) {
                    latest = version;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return latest;
    }

    /**
     * 解析 flink-dist jar 文件名里的版本；不是 dist 包时返回 null。
     *
     * <p>兼容 2.x 的 {@code flink-dist-2.2.0.jar} 与 1.x 的
     * {@code flink-dist_2.12-1.20.0.jar}：从末尾向前找第一个完整版本段，
     * 避免把 1.x 文件名里的 Scala 版本（2.12）当成 Flink 版本。</p>
     */
    static FlinkVersion fromDistJar(String fileName) {
        if (fileName == null || !fileName.startsWith("flink-dist")) {
            return null;
        }
        String base = fileName.endsWith(".jar")
                ? fileName.substring(0, fileName.length() - ".jar".length()) : fileName;
        String[] parts = base.substring("flink-dist".length()).split("[-_]");
        for (int i = parts.length - 1; i >= 0; i--) {
            Matcher matcher = VERSION.matcher(parts[i]);
            if (matcher.matches()) {
                return fromMatcher(matcher);
            }
        }
        return null;
    }

    boolean isAtLeast(FlinkVersion required) {
        return compareTo(required) >= 0;
    }

    @Override
    public int compareTo(FlinkVersion other) {
        int result = Integer.compare(major, other.major);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(minor, other.minor);
        return result != 0 ? result : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
