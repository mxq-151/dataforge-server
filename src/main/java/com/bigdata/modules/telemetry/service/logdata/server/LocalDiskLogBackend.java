package com.bigdata.modules.telemetry.service.logdata.server;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.common.model.BackendType;
import com.bigdata.modules.telemetry.service.logdata.model.LogDataType;
import com.bigdata.modules.telemetry.service.logdata.model.LogEntry;
import com.bigdata.modules.telemetry.common.model.TimeRange;
import com.bigdata.modules.telemetry.common.model.BackendCapability;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.bigdata.modules.telemetry.service.logdata.spi.LogBackend;
import com.bigdata.modules.telemetry.service.logdata.model.LogQueryResult;
import com.bigdata.modules.telemetry.service.logdata.model.LogQuerySpec;
import com.bigdata.modules.telemetry.service.logdata.support.CursorCodec;
import com.bigdata.modules.telemetry.service.logdata.support.LogFilterEngine;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.text.ParsePosition;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import com.bigdata.modules.telemetry.config.properties.LocalDiskConfig;
import com.bigdata.modules.telemetry.config.properties.LogSourceConfig;
import com.bigdata.modules.telemetry.util.Strings;

/**
 * 本地 / 共享挂载目录日志后端。
 *
 * <p>时间范围过滤靠逐行时间戳，没有时间索引，因此必须有界：按文件 mtime 裁剪候选文件，
 * 受 {@code maxScanBytes} / {@code maxBytes} / {@code limit} / {@code timeout} 约束，
 * 触达任一预算即停并返回 {@code truncated=true} 与可继续的游标。</p>
 *
 * <p>扫描统计（文件数、字节数、行数）随结果返回，明确告知调用方这不是全量结果。</p>
 */
public final class LocalDiskLogBackend implements LogBackend {

    private static final int MAX_LIMIT = 2000;

    private final String backendId;
    private final LocalDiskConfig config;
    private final ObjectMapper objectMapper;

    public LocalDiskLogBackend(String backendId, LocalDiskConfig config, ObjectMapper objectMapper) {
        this.backendId = backendId;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    public String backendId() {
        return backendId;
    }

    @Override
    public BackendType backendType() {
        return BackendType.LOCAL_DISK;
    }

    @Override
    public BackendCapability evaluate(LogQuerySpec spec) {
        if (spec.dataType() != LogDataType.SERVICE) {
            return BackendCapability.unavailable(CapabilityCode.LOG_DATA_TYPE_MISMATCH,
                    "local-disk 只承载 log.service，任务日志请用 yarn", List.of());
        }
        Resolution resolution = resolveSource(spec);
        if (resolution.source() == null) {
            return BackendCapability.unavailable(resolution.code(), resolution.reason(),
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.local-disk[].sources",
                            "为该服务/组件/host/stream 配置日志路径")));
        }
        if (config.getAllowRootDirs() == null || config.getAllowRootDirs().isEmpty()) {
            return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                    "未配置 allow-root-dirs，拒绝读取任意路径",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.local-disk[].allow-root-dirs",
                            "配置允许访问的日志根目录")));
        }
        LogSourceConfig source = resolution.source();
        TimeRange range = spec.timeRange();
        boolean timeFilter = range != null && (range.start() != null || range.end() != null);
        if (timeFilter && Strings.isBlank(source.getTimestampPattern())) {
            return BackendCapability.unavailable(CapabilityCode.TIME_FILTER_UNSUPPORTED,
                    "日志源未配置 timestamp-pattern，无法按时间范围过滤",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.local-disk[].sources[].timestamp-pattern",
                            "配置行首时间戳格式后可按时间范围过滤")));
        }
        if (!Strings.isBlank(source.getTimestampPattern())) {
            try {
                formatter(source);
            } catch (IllegalArgumentException e) {
                return BackendCapability.unavailable(CapabilityCode.NOT_CONFIGURED,
                        "timestamp-pattern 非法: " + e.getMessage(), List.of());
            }
        }
        return BackendCapability.available();
    }

    @Override
    public LogQueryResult fetch(LogQuerySpec spec) {
        LogSourceConfig source = resolveSource(spec).source();
        if (source == null) {
            throw new BackendException(CapabilityCode.NOT_CONFIGURED, "没有匹配的本地日志源");
        }
        LogFilterEngine filter;
        try {
            filter = LogFilterEngine.compile(spec.filter());
        } catch (IllegalArgumentException e) {
            throw new BackendException(CapabilityCode.INVALID_ARGUMENT, e.getMessage());
        }

        TimeRange range = spec.timeRange();
        boolean timeFilter = range != null && (range.start() != null || range.end() != null);
        LineTimestampParser parser = new LineTimestampParser(source);
        String filterHash = CursorCodec.hash(objectMapper, spec.filter().keywords(),
                spec.filter().excludeKeywords(), spec.filter().matchMode(),
                spec.filter().regex(), spec.filter().caseSensitive());
        String rangeHash = CursorCodec.hash(objectMapper,
                range == null ? null : range.start(), range == null ? null : range.end());
        FileCursor cursor = spec.cursor() == null ? null
                : CursorCodec.decode(spec.cursor(), FileCursor.class, objectMapper);

        List<Path> files = candidateFiles(source, range);
        if (files.isEmpty()) {
            // 候选文件被时间范围全部裁剪掉：这是合法的空结果，不是错误。
            return new LogQueryResult(List.of(), null, false,
                    new LogQueryResult.ScanStats(0, 0, 0, 0), List.of());
        }
        int startIndex = 0;
        if (cursor != null) {
            verifyCursor(cursor, spec, filterHash, rangeHash);
            startIndex = indexOf(files, cursor.file());
            if (startIndex < 0) {
                throw new BackendException(CapabilityCode.CURSOR_EXPIRED,
                        "游标指向的文件已不在候选列表（可能已轮转），请从第一页重新查询");
            }
        }

        int limit = Math.max(1, Math.min(spec.limit() <= 0 ? config.getLimits().getMaxLines() : spec.limit(),
                MAX_LIMIT));
        long maxBytes = spec.maxBytes() > 0 ? spec.maxBytes() : config.getLimits().getMaxBytes();
        long maxScanBytes = config.getLimits().getMaxScanBytes();
        long deadline = System.nanoTime() + Math.max(config.getLimits().getTimeoutMs(), 1) * 1_000_000L;

        List<LogEntry> entries = new ArrayList<>();
        int filesScanned = 0;
        long scannedBytes = 0;
        long scannedLines = 0;
        long payloadBytes = 0;
        boolean truncated = false;
        FileCursor nextCursor = null;
        boolean firstFile = true;

        outer:
        for (int i = startIndex; i < files.size(); i++) {
            Path raw = files.get(i);
            Path file = allowedRealPath(raw);
            long offset = cursor != null && i == startIndex ? cursor.offset() : 0L;
            long mtime = mtime(file);
            long lineNo = cursor != null && i == startIndex ? cursor.lineNo() : 0L;
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                if (offset > raf.length()) {
                    throw new BackendException(CapabilityCode.CURSOR_EXPIRED,
                            "游标偏移超过文件长度，文件可能已被截断");
                }
                raf.seek(offset);
                filesScanned++;
                LineReader reader = new LineReader(raf);
                long position = offset;
                Instant lastTimestamp = null;
                String line;
                while ((line = reader.next()) != null) {
                    long lineBytes = reader.lastLineBytes();
                    lineNo++;
                    if (timeFilter) {
                        Instant parsed = parser.parse(line);
                        if (parsed != null) {
                            lastTimestamp = parsed;
                        } else if (firstFile && lastTimestamp == null && !line.isBlank()) {
                            throw new BackendException(CapabilityCode.TIME_FILTER_UNSUPPORTED,
                                    "首行无法按 timestamp-pattern 解析，无法执行时间范围过滤",
                                    List.of(new Requirement(RequirementKind.CONFIG,
                                            "telemetry.backends.local-disk[].sources[].timestamp-pattern",
                                            "调整时间戳格式或去掉时间范围")));
                        }
                    }
                    scannedBytes += lineBytes;
                    scannedLines++;
                    boolean inRange = !timeFilter
                            || (lastTimestamp != null
                            && (range.start() == null || !lastTimestamp.isBefore(range.start()))
                            && (range.end() == null || !lastTimestamp.isAfter(range.end())));
                    if (inRange && filter.matches(line)) {
                        long linePayload = line.getBytes(StandardCharsets.UTF_8).length + 1L;
                        if (payloadBytes + linePayload > maxBytes) {
                            truncated = true;
                            nextCursor = cursorOf(file, position, lineNo - 1, mtime,
                                    filterHash, rangeHash, spec);
                            break outer;
                        }
                        entries.add(new LogEntry(lastTimestamp, spec.stream(),
                                spec.target().hostId(), line, Map.of()));
                        payloadBytes += linePayload;
                        if (entries.size() >= limit) {
                            boolean hasMore = position + lineBytes < raf.length() || i < files.size() - 1;
                            truncated = hasMore;
                            if (hasMore) {
                                nextCursor = cursorOf(file, position + lineBytes, lineNo, mtime,
                                        filterHash, rangeHash, spec);
                            }
                            break outer;
                        }
                    }
                    position += lineBytes;
                    if (scannedBytes >= maxScanBytes) {
                        truncated = true;
                        nextCursor = cursorOf(file, position, lineNo, mtime, filterHash, rangeHash, spec);
                        break outer;
                    }
                    if ((scannedLines & 0x3FF) == 0 && System.nanoTime() > deadline) {
                        truncated = true;
                        nextCursor = cursorOf(file, position, lineNo, mtime, filterHash, rangeHash, spec);
                        break outer;
                    }
                }
                firstFile = false;
            } catch (IOException e) {
                throw new BackendException(CapabilityCode.IO_ERROR,
                        "读取日志文件失败: " + raw + " (" + e.getMessage() + ")");
            }
        }

        LogQueryResult.ScanStats scan = new LogQueryResult.ScanStats(
                filesScanned, scannedBytes, scannedLines, entries.size());
        return new LogQueryResult(entries,
                nextCursor == null ? null : CursorCodec.encode(nextCursor, objectMapper),
                truncated, scan, List.of());
    }

    // ------------------------------------------------------------------ 源定位与文件

    private Resolution resolveSource(LogQuerySpec spec) {
        List<LogSourceConfig> matches = new ArrayList<>();
        for (LogSourceConfig source : config.getSources()) {
            if (!spec.target().serviceType().equals(source.getServiceType())
                    || !spec.target().componentId().equals(source.getComponentId())
                    || !spec.stream().equals(source.getStream())) {
                continue;
            }
            matches.add(source);
        }
        if (matches.isEmpty()) {
            return new Resolution(null, CapabilityCode.NOT_CONFIGURED,
                    "没有匹配的日志源: " + spec.target().serviceType() + "/"
                            + spec.target().componentId() + "/" + spec.stream());
        }
        if (!spec.target().clusterScoped()) {
            for (LogSourceConfig source : matches) {
                if (spec.target().hostId().equals(source.getHostId())) {
                    return new Resolution(source, CapabilityCode.OK, null);
                }
            }
            return new Resolution(null, CapabilityCode.NOT_CONFIGURED,
                    "该 host 没有配置日志源: " + spec.target().hostId());
        }
        if (matches.size() == 1) {
            return new Resolution(matches.get(0), CapabilityCode.OK, null);
        }
        return new Resolution(null, CapabilityCode.HOST_REQUIRED,
                "cluster 目标命中多个日志源，请指定 hostId");
    }

    private List<Path> candidateFiles(LogSourceConfig source, TimeRange range) {
        Path raw = Paths.get(source.getPath());
        String name = raw.getFileName() == null ? "" : raw.getFileName().toString();
        List<Path> files = new ArrayList<>();
        if (name.contains("*") || name.contains("?")) {
            Path dir = raw.getParent();
            if (dir == null || !Files.isDirectory(dir)) {
                throw new BackendException(CapabilityCode.NOT_CONFIGURED,
                        "通配符日志路径的父目录不存在: " + source.getPath());
            }
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + name);
            try (Stream<Path> stream = Files.list(dir)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> matcher.matches(path.getFileName()))
                        .forEach(files::add);
            } catch (IOException e) {
                throw new BackendException(CapabilityCode.IO_ERROR,
                        "列举日志目录失败: " + dir + " (" + e.getMessage() + ")");
            }
        } else if (Files.isDirectory(raw)) {
            try (Stream<Path> stream = Files.list(raw)) {
                stream.filter(Files::isRegularFile).forEach(files::add);
            } catch (IOException e) {
                throw new BackendException(CapabilityCode.IO_ERROR,
                        "列举日志目录失败: " + raw + " (" + e.getMessage() + ")");
            }
        } else {
            files.add(raw);
        }
        if (range != null && range.start() != null) {
            long startMillis = range.start().toEpochMilli();
            files.removeIf(path -> mtime(path) < startMillis);
        }
        // 统一用 realpath：游标里存的是 realpath，候选列表必须与之一致（macOS /var -> /private/var）。
        List<Path> resolved = new ArrayList<>();
        for (Path path : files) {
            resolved.add(allowedRealPath(path));
        }
        resolved.sort(Comparator.comparingLong(LocalDiskLogBackend::mtime).thenComparing(Path::toString));
        return resolved;
    }

    private Path allowedRealPath(Path file) {
        Path real;
        try {
            real = file.toRealPath();
        } catch (IOException e) {
            throw new BackendException(CapabilityCode.NOT_CONFIGURED,
                    "日志文件不存在或不可读: " + file);
        }
        for (String root : config.getAllowRootDirs()) {
            if (root == null || root.isBlank()) {
                continue;
            }
            try {
                Path rootReal = Paths.get(root).toRealPath();
                if (real.startsWith(rootReal)) {
                    return real;
                }
            } catch (IOException ignored) {
                // 根目录不存在则跳过
            }
        }
        throw new BackendException(CapabilityCode.PERMISSION_DENIED,
                "日志路径不在 allow-root-dirs 内: " + file);
    }

    private void verifyCursor(FileCursor cursor, LogQuerySpec spec, String filterHash, String rangeHash) {
        if (!filterHash.equals(cursor.filterHash()) || !rangeHash.equals(cursor.rangeHash())
                || !spec.stream().equals(cursor.stream())) {
            throw new BackendException(CapabilityCode.CURSOR_MISMATCH,
                    "过滤条件或时间范围已变化，请从第一页重新查询");
        }
        Path file = Paths.get(cursor.file());
        if (!Files.exists(file) || mtime(file) != cursor.mtime()) {
            throw new BackendException(CapabilityCode.CURSOR_EXPIRED,
                    "游标指向的文件已轮转或修改，请从第一页重新查询");
        }
    }

    private FileCursor cursorOf(Path file, long offset, long lineNo, long mtime,
                                String filterHash, String rangeHash, LogQuerySpec spec) {
        return new FileCursor(file.toString(), offset, lineNo, mtime, filterHash, rangeHash,
                spec.stream(), spec.target().serviceType() + "/" + spec.target().componentId()
                + "/" + spec.target().hostId());
    }

    private static int indexOf(List<Path> files, String path) {
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).toString().equals(path)) {
                return i;
            }
        }
        return -1;
    }

    private static long mtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private DateTimeFormatter formatter(LogSourceConfig source) {
        return DateTimeFormatter.ofPattern(source.getTimestampPattern());
    }

    private record Resolution(LogSourceConfig source, CapabilityCode code, String reason) {
    }

    /** 游标结构：文件身份 + 偏移 + 条件指纹。 */
    record FileCursor(String file, long offset, long lineNo, long mtime,
                      String filterHash, String rangeHash, String stream, String target) {
    }

    /** 逐行时间戳解析；解析失败返回 null，由调用方决定跳过还是报错。 */
    private static final class LineTimestampParser {
        private final DateTimeFormatter formatter;
        private final ZoneId zone;

        private LineTimestampParser(LogSourceConfig source) {
            this.formatter = Strings.isBlank(source.getTimestampPattern()) ? null
                    : DateTimeFormatter.ofPattern(source.getTimestampPattern());
            this.zone = Strings.isBlank(source.getZone()) ? ZoneId.systemDefault() : ZoneId.of(source.getZone());
        }

        Instant parse(String line) {
            if (formatter == null) {
                return null;
            }
            ParsePosition position = new ParsePosition(0);
            try {
                TemporalAccessor accessor = formatter.parse(line, position);
                if (accessor == null || position.getIndex() == 0) {
                    return null;
                }
                try {
                    return LocalDateTime.from(accessor).atZone(zone).toInstant();
                } catch (DateTimeException e) {
                    return LocalDate.from(accessor).atStartOfDay(zone).toInstant();
                }
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** 按换行切分并记录每行消耗的字节数，保证游标按行边界推进。 */
    private static final class LineReader {
        private final RandomAccessFile file;
        private final byte[] buffer = new byte[64 * 1024];
        private int position;
        private int limit;
        private int lastLineBytes;

        private LineReader(RandomAccessFile file) {
            this.file = file;
        }

        String next() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            lastLineBytes = 0;
            while (true) {
                if (position >= limit && !fill()) {
                    if (out.size() == 0) {
                        return null;
                    }
                    break;
                }
                int index = indexOfNewline();
                if (index < 0) {
                    int length = limit - position;
                    out.write(buffer, position, length);
                    lastLineBytes += length;
                    position = limit;
                } else {
                    int length = index - position + 1;
                    out.write(buffer, position, length);
                    lastLineBytes += length;
                    position = index + 1;
                    break;
                }
            }
            byte[] bytes = out.toByteArray();
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == '\n') {
                length--;
            }
            if (length > 0 && bytes[length - 1] == '\r') {
                length--;
            }
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        }

        long lastLineBytes() {
            return lastLineBytes;
        }

        private boolean fill() throws IOException {
            int read = file.read(buffer);
            if (read <= 0) {
                return false;
            }
            position = 0;
            limit = read;
            return true;
        }

        private int indexOfNewline() {
            for (int i = position; i < limit; i++) {
                if (buffer[i] == '\n') {
                    return i;
                }
            }
            return -1;
        }
    }
}
