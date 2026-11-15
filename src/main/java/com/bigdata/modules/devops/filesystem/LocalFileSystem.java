package com.bigdata.modules.devops.filesystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

@Component
public class LocalFileSystem implements FileSystem {

    private static final Logger logger = LoggerFactory.getLogger(LocalFileSystem.class);

    private String basePath;

    @Override
    public void initialize(Map<String, String> config) throws Exception {
        this.basePath = config.getOrDefault("basePath", System.getProperty("user.home") + "/filebrowser");

        Path baseDir = Paths.get(basePath);
        if (!Files.exists(baseDir)) {
            Files.createDirectories(baseDir);
        }

        logger.info("Local file system initialized with base path: {}", basePath);
    }

    @Override
    public boolean isConnected() {
        return basePath != null && !basePath.isEmpty() && Files.exists(Paths.get(basePath));
    }

    @Override
    public List<Map<String, Object>> listFiles(String path) throws Exception {
        String fullPath = normalizePath(path);
        Path dirPath = Paths.get(fullPath);

        if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
            throw new FileNotFoundException("Directory not found: " + path);
        }

        List<Map<String, Object>> fileList = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)) {
            for (Path filePath : stream) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(filePath, BasicFileAttributes.class);

                    Map<String, Object> fileInfo = new LinkedHashMap<>();
                    fileInfo.put("name", filePath.getFileName().toString());
                    fileInfo.put("isDir", attrs.isDirectory());
                    fileInfo.put("size", attrs.isDirectory() ? 0 : attrs.size());
                    fileInfo.put("modifiedTime", formatTime(attrs.lastModifiedTime().toMillis()));
                    fileInfo.put("permissions", getPermissionsString(filePath.toFile()));
                    fileInfo.put("path", getRelativePath(filePath));

                    fileList.add(fileInfo);
                } catch (IOException e) {
                    logger.warn("Failed to read attributes for: {}", filePath, e);
                }
            }
        }

        fileList.sort((a, b) -> {
            boolean aDir = (Boolean) a.get("isDir");
            boolean bDir = (Boolean) b.get("isDir");
            if (aDir != bDir) return aDir ? -1 : 1;
            return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
        });

        return fileList;
    }

    @Override
    public void createDirectory(String path) throws Exception {
        String fullPath = normalizePath(path);
        Path dirPath = Paths.get(fullPath);

        if (Files.exists(dirPath)) {
            throw new FileAlreadyExistsException("Directory already exists: " + path);
        }

        Files.createDirectories(dirPath);
        logger.info("Created directory: {}", path);
    }

    @Override
    public void deleteFile(String path) throws Exception {
        String fullPath = normalizePath(path);
        Path targetPath = Paths.get(fullPath);

        if (!Files.exists(targetPath)) {
            throw new FileNotFoundException("File not found: " + path);
        }

        if (Files.isDirectory(targetPath)) {
            Files.walk(targetPath)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        } else {
            Files.delete(targetPath);
        }
        logger.info("Deleted: {}", path);
    }

    @Override
    public void renameFile(String oldPath, String newPath) throws Exception {
        String fullOldPath = normalizePath(oldPath);
        String fullNewPath = normalizePath(newPath);

        Path oldFilePath = Paths.get(fullOldPath);
        Path newFilePath = Paths.get(fullNewPath);

        if (!Files.exists(oldFilePath)) {
            throw new FileNotFoundException("Source file not found: " + oldPath);
        }

        if (Files.exists(newFilePath)) {
            throw new FileAlreadyExistsException("Target already exists: " + newPath);
        }

        Files.move(oldFilePath, newFilePath, StandardCopyOption.REPLACE_EXISTING);
        logger.info("Renamed: {} -> {}", oldPath, newPath);
    }

    @Override
    public InputStream readFile(String path) throws Exception {
        String fullPath = normalizePath(path);
        Path filePath = Paths.get(fullPath);

        if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
            throw new FileNotFoundException("File not found or is directory: " + path);
        }

        long maxSize = 10 * 1024 * 1024; // 10MB
        if (Files.size(filePath) > maxSize) {
            throw new IOException("File too large for preview (max 10MB)");
        }

        return new BufferedInputStream(Files.newInputStream(filePath));
    }

    @Override
    public void uploadFile(String path, InputStream inputStream, long size) throws Exception {
        String fullPath = normalizePath(path);
        Path targetPath = Paths.get(fullPath);

        Path parentDir = targetPath.getParent();
        if (parentDir != null && !Files.exists(parentDir)) {
            Files.createDirectories(parentDir);
        }

        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(targetPath))) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
            }
            logger.info("Uploaded file: {} ({} bytes)", path, totalRead);
        }
    }

    @Override
    public boolean exists(String path) throws Exception {
        String fullPath = normalizePath(path);
        return Files.exists(Paths.get(fullPath));
    }

    @Override
    public void close() throws Exception {
        basePath = null;
        logger.info("Local file system closed");
    }

    /**
     * 把接口传入的「根内相对路径」解析为绝对路径，并阻断越界。
     *
     * <p>{@code ..}、盘符、UNC、软链接都可能把最终路径带出 basePath。这里统一做四件事：
     * 归一化分隔符 → 拒绝绝对路径形态 → {@code normalize()} 后确认仍在根内 →
     * 对已存在的最近祖先做真实路径校验（防软链接逃逸）。</p>
     */
    private String normalizePath(String path) throws IOException {
        return resolveInsideBase(path).toString();
    }

    private Path resolveInsideBase(String path) throws IOException {
        Path base = Paths.get(basePath).toAbsolutePath().normalize();
        String relative = path == null ? "" : path.trim();
        // Windows 分隔符在 POSIX 上只是普通字符，先统一，避免 "..\\x" 绕过校验
        relative = relative.replace('\\', '/');
        // 盘符 / UNC 属于绝对路径，resolve 会直接顶掉根目录
        if (relative.matches("^[A-Za-z]:.*") || relative.startsWith("//")) {
            throw new IOException("非法路径（越界访问）: " + path);
        }
        while (relative.startsWith("/")) {
            relative = relative.substring(1);
        }

        Path target = base.resolve(relative).normalize();
        if (!target.startsWith(base)) {
            throw new IOException("非法路径（越界访问）: " + path);
        }
        assertNoSymlinkEscape(base, target, path);
        return target;
    }

    /**
     * 软链接可把目录指向根外，因此对目标本身或最近的已存在祖先取真实路径再校验一次。
     * 目标尚不存在（新建/上传）时校验最近的祖先，避免通过软链接创建根外文件。
     */
    private void assertNoSymlinkEscape(Path base, Path target, String rawPath) throws IOException {
        if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path existing = target;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return;
        }
        Path realBase = base.toRealPath();
        Path realExisting = existing.toRealPath();
        if (!realExisting.startsWith(realBase)) {
            throw new IOException("非法路径（越界访问）: " + rawPath);
        }
    }

    private String getRelativePath(Path absolutePath) {
        Path base = Paths.get(basePath);
        Path relative = base.relativize(absolutePath);
        return "/" + relative.toString().replace("\\", "/");
    }

    private String formatTime(long millis) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(millis));
    }

    private String getPermissionsString(File file) {
        StringBuilder perms = new StringBuilder();
        perms.append(file.isDirectory() ? "d" : "-");
        perms.append(file.canRead() ? "r" : "-");
        perms.append(file.canWrite() ? "w" : "-");
        perms.append(file.canExecute() ? "x" : "-");
        perms.append("r--");
        perms.append("r--");
        return perms.toString();
    }
}
