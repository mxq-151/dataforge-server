package com.bigdata.modules.logs.adapter;

import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogArtifact;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 本地 / 共享挂载目录日志适配器。
 *
 * <p>用于 dataforge-server 能直接访问日志文件的环境（本机、NFS 挂载、K8s 挂载卷）。
 * 访问范围严格限定在 {@code options.root-dirs} 内：路径先归一化再 {@code toRealPath}，
 * 任何越界（{@code ..}、符号链接逃逸）都会被拒绝。</p>
 *
 * <p>游标为<b>字节偏移</b>，按行边界切分；同时支持产物列举与下载（需显式开启
 * {@code options.allow-download}）。</p>
 */
@Component
public class FileLogAdapter implements LogAdapter {

    private static final Logger log = LoggerFactory.getLogger(FileLogAdapter.class);
    private static final int MAX_ARTIFACTS = 500;

    @Override
    public boolean supports(ServiceType serviceType, LogKind logKind, ComponentRole role) {
        return serviceType == ServiceType.FILE;
    }

    @Override
    public boolean supportsArtifacts() {
        return true;
    }

    @Override
    public AdapterResult query(LogQueryContext context) {
        String rawPath = context.ref("path", "file", "filePath");
        if (rawPath == null) {
            throw new LogAdapterException(LogAdapterException.Code.BAD_REQUEST,
                    "缺少 ref.path");
        }
        Path file = resolve(context.getSource(), rawPath);
        if (Files.isDirectory(file)) {
            throw new LogAdapterException(LogAdapterException.Code.BAD_REQUEST,
                    "path 指向目录，请使用 /api/logs/artifacts 列举文件: " + rawPath);
        }

        long offset = Math.max(context.getOffset(), 0L);
        long fileSize;
        try {
            fileSize = Files.size(file);
        } catch (IOException e) {
            throw new LogAdapterException(LogAdapterException.Code.NOT_FOUND,
                    "日志文件不可读: " + rawPath, e);
        }
        if (offset >= fileSize) {
            AdapterResult empty = AdapterResult.of("", 0, offset, false);
            empty.setResolvedPath(file.toString());
            return empty;
        }

        long maxRead = Math.min(context.getMaxBytes() + 1L, fileSize - offset);
        byte[] buffer = new byte[(int) Math.min(maxRead, Integer.MAX_VALUE)];
        int read;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(offset);
            read = raf.read(buffer);
        } catch (IOException e) {
            throw new LogAdapterException(LogAdapterException.Code.UNAVAILABLE,
                    "读取日志文件失败: " + e.getMessage(), e);
        }
        if (read <= 0) {
            return AdapterResult.of("", 0, offset, false);
        }

        String windowText = new String(buffer, 0, read, StandardCharsets.UTF_8);
        // 只有「本次没有读到文件末尾」时才按最后一个换行切分；读到 EOF 时必须保留最后一行
        if (offset + read < fileSize) {
            int take = lastNewlineIndex(buffer, read);
            if (take > 0) {
                windowText = new String(buffer, 0, take, StandardCharsets.UTF_8);
            }
        }

        BudgetLimiter.Result budget = BudgetLimiter.limitText(windowText, context.getMaxLines(),
                context.getMaxBytes());
        int consumedBytes = bytesOfFirstLines(windowText, budget.getLines().size());
        boolean hasMore = offset + consumedBytes < fileSize;

        AdapterResult result = AdapterResult.of(budget.getContent(), budget.getLines().size(),
                offset + consumedBytes, hasMore);
        result.setTruncated(budget.isTruncated());
        result.setResolvedPath(file.toString());
        return result;
    }

    @Override
    public List<LogArtifact> listArtifacts(LogQueryContext context) {
        String rawPath = context.ref("path", "file", "filePath");
        if (rawPath == null) {
            LogAccessProperties.Options options = context.getSource().getConfig().getOptions();
            if (options.getRootDirs() == null || options.getRootDirs().isEmpty()) {
                throw new LogAdapterException(LogAdapterException.Code.BAD_REQUEST,
                        "缺少 ref.path，且该日志源未配置 root-dirs");
            }
            rawPath = options.getRootDirs().get(0);
        }
        Path target = resolve(context.getSource(), rawPath);
        List<LogArtifact> artifacts = new ArrayList<>();
        if (Files.isRegularFile(target)) {
            artifacts.add(toArtifact(target, false));
            return artifacts;
        }
        try (Stream<Path> stream = Files.list(target)) {
            stream.sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .limit(MAX_ARTIFACTS)
                    .forEach(path -> artifacts.add(toArtifact(path, Files.isDirectory(path))));
        } catch (IOException e) {
            throw new LogAdapterException(LogAdapterException.Code.UNAVAILABLE,
                    "列举日志目录失败: " + e.getMessage(), e);
        }
        return artifacts;
    }

    @Override
    public byte[] fetchArtifact(LogQueryContext context, String artifactPath) {
        LogAccessProperties.Options options = context.getSource().getConfig().getOptions();
        if (!options.isAllowDownload()) {
            throw new LogAdapterException(LogAdapterException.Code.BAD_REQUEST,
                    "该日志源未开启产物下载（logs.sources[].options.allow-download）");
        }
        Path file = resolve(context.getSource(), artifactPath);
        if (Files.isDirectory(file)) {
            throw new LogAdapterException(LogAdapterException.Code.BAD_REQUEST,
                    "目标不是文件: " + artifactPath);
        }
        try {
            long size = Files.size(file);
            if (size > options.getMaxDownloadBytes()) {
                throw new LogAdapterException(LogAdapterException.Code.BAD_REQUEST,
                        "文件大小 " + size + " 字节超过下载上限 " + options.getMaxDownloadBytes()
                                + " 字节，请使用 /api/logs/query 分段读取");
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new LogAdapterException(LogAdapterException.Code.UNAVAILABLE,
                    "读取产物失败: " + e.getMessage(), e);
        }
    }

    private LogArtifact toArtifact(Path path, boolean directory) {
        long size = 0;
        LocalDateTime modified = null;
        try {
            if (!directory) {
                size = Files.size(path);
            }
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            Instant instant = attrs.lastModifiedTime().toInstant();
            modified = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        } catch (IOException e) {
            log.debug("读取文件属性失败: {}", path, e);
        }
        return new LogArtifact(path.getFileName().toString(), path.toString(), size, modified, directory);
    }

    /**
     * 把调用方给的路径解析为真实路径，并校验落在配置的根目录内。
     */
    private Path resolve(LogSource source, String rawPath) {
        List<String> roots = source.getConfig().getOptions().getRootDirs();
        if (roots == null || roots.isEmpty()) {
            throw new LogAdapterException(LogAdapterException.Code.BAD_REQUEST,
                    "该日志源未配置 root-dirs，无法定位文件: " + rawPath);
        }
        Path candidate = Paths.get(rawPath);
        Path resolved = null;
        if (candidate.isAbsolute()) {
            resolved = candidate.normalize();
        } else {
            for (String root : roots) {
                if (root == null || root.isBlank()) {
                    continue;
                }
                Path rootPath = Paths.get(root);
                Path path = rootPath.resolve(candidate).normalize();
                if (Files.exists(path)) {
                    resolved = path;
                    break;
                }
            }
            if (resolved == null) {
                throw new LogAdapterException(LogAdapterException.Code.NOT_FOUND,
                        "在配置的 root-dirs 下找不到文件: " + rawPath);
            }
        }

        Path real;
        try {
            real = resolved.toRealPath();
        } catch (IOException e) {
            throw new LogAdapterException(LogAdapterException.Code.NOT_FOUND,
                    "文件不存在: " + rawPath, e);
        }
        for (String root : roots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            try {
                Path rootReal = Paths.get(root).toRealPath();
                if (real.startsWith(rootReal)) {
                    return real;
                }
            } catch (IOException ignored) {
                // 根目录不存在时跳过；所有根都不匹配则拒绝
            }
        }
        throw new LogAdapterException(LogAdapterException.Code.BAD_REQUEST,
                "路径不在允许的 root-dirs 内: " + rawPath);
    }

    private int lastNewlineIndex(byte[] buffer, int length) {
        for (int i = length - 1; i >= 0; i--) {
            if (buffer[i] == '\n') {
                return i + 1;
            }
        }
        return -1;
    }

    static int bytesOfFirstLines(String content, int lineCount) {
        if (content == null || content.isEmpty() || lineCount <= 0) {
            return 0;
        }
        int seen = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                seen++;
                if (seen == lineCount) {
                    return content.substring(0, i + 1).getBytes(StandardCharsets.UTF_8).length;
                }
            }
        }
        return content.getBytes(StandardCharsets.UTF_8).length;
    }
}
