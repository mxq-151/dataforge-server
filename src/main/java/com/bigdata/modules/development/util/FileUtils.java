package com.bigdata.modules.development.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public final class FileUtils {

    private FileUtils() {
    }

    public static void deleteDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (Stream<Path> paths = Files.walk(directory).sorted(java.util.Comparator.reverseOrder())) {
                paths.forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new RuntimeException("删除文件失败: " + path, e);
                    }
                });
            }
        }
    }
}
