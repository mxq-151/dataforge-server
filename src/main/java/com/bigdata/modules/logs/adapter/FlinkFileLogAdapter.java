package com.bigdata.modules.logs.adapter;

import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.model.ComponentRole;
import com.bigdata.modules.logs.model.LogKind;
import com.bigdata.modules.logs.model.LogSource;
import com.bigdata.modules.logs.model.ServiceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Flink 原始日志适配器：JobManager / TaskManager 的 {@code *.log} 与 {@code *.out}（<b>原始日志层</b>）。
 *
 * <h3>部署形态</h3>
 * <p>当前 P0 覆盖「日志目录对 dataforge-server 可见」的形态（独立/会话集群本机、共享挂载、K8s 挂载卷）。
 * K8s 按 pod 标签取容器日志、对象存储/日志平台接入属于 P1，本适配器不做，配置了也只会让
 * {@code /sources} 标注不可用原因。</p>
 *
 * <h3>多 TM 合并与时间排序</h3>
 * <p>同一角色的所有目录、所有匹配文件都会被读取（超过 {@code max-file-bytes} 的大文件只读末尾），
 * 按行首时间戳归并为「日志块」后按时间排序，再按游标切页。无时间戳的行（如 stdout、堆栈）
 * 归入上一条日志块；整文件无时间戳时用文件修改时间兜底。</p>
 *
 * <p>游标与既有实现一致：行偏移。若检索期间有新日志追加或文件轮转，偏移可能有轻微漂移。</p>
 */
@Component
public class FlinkFileLogAdapter implements LogAdapter {

    private static final Logger log = LoggerFactory.getLogger(FlinkFileLogAdapter.class);
    private static final int MAX_FILES = 200;

    @Override
    public boolean supports(ServiceType serviceType, LogKind logKind, ComponentRole role) {
        if (serviceType != ServiceType.FLINK) {
            return false;
        }
        if (logKind != LogKind.RUNTIME && logKind != LogKind.STDOUT) {
            return false;
        }
        return role == ComponentRole.JOB_MANAGER || role == ComponentRole.TASK_MANAGER
                || role == ComponentRole.GENERIC;
    }

    @Override
    public AdapterResult query(LogQueryContext context) {
        LogSource source = context.getSource();
        LogAccessProperties.Options options = source.getConfig().getOptions();
        ComponentRole role = context.getRole();
        LogKind kind = context.getLogKind();

        boolean wantJobManager = role == ComponentRole.JOB_MANAGER || role == ComponentRole.GENERIC;
        boolean wantTaskManager = role == ComponentRole.TASK_MANAGER || role == ComponentRole.GENERIC;
        List<String> missingDirs = new ArrayList<>();
        List<Path> files = new ArrayList<>();

        boolean logKindRuntime = kind == LogKind.RUNTIME;
        if (wantJobManager) {
            files.addAll(collect(options.getJobManagerLogDirs(),
                    logKindRuntime ? options.getJobManagerLogPattern() : options.getJobManagerStdoutPattern(),
                    missingDirs));
        }
        if (wantTaskManager) {
            files.addAll(collect(options.getTaskManagerLogDirs(),
                    logKindRuntime ? options.getTaskManagerLogPattern() : options.getTaskManagerStdoutPattern(),
                    missingDirs));
        }
        if (files.isEmpty()) {
            throw new LogAdapterException(LogAdapterException.Code.NOT_FOUND, emptyReason(role, kind, options, missingDirs));
        }

        boolean headTruncated = false;
        List<Entry> entries = new ArrayList<>();
        for (Path file : files) {
            try {
                ReadResult read = readTail(file, Math.max(options.getMaxFileBytes(), 64 * 1024));
                headTruncated = headTruncated || read.truncatedHead;
                entries.addAll(parse(file, read.content, modifiedAt(file)));
            } catch (IOException e) {
                log.warn("读取 Flink 日志文件失败: {}", file, e);
            }
        }
        if (entries.isEmpty()) {
            throw new LogAdapterException(LogAdapterException.Code.NOT_FOUND,
                    "匹配到的 Flink 日志文件为空或不可读");
        }

        entries.sort(Comparator.comparing(Entry::getTimestamp,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Entry::getFileName));

        List<String> lines = new ArrayList<>();
        for (Entry entry : entries) {
            if (!inRange(entry.timestamp, context.getStartTime(), context.getEndTime())) {
                continue;
            }
            lines.addAll(entry.lines);
        }

        int total = lines.size();
        int from = (int) Math.min(Math.max(context.getOffset(), 0L), total);
        BudgetLimiter.Result budget = BudgetLimiter.limit(lines.subList(from, total),
                context.getMaxLines(), context.getMaxBytes());
        long nextOffset = from + budget.getLines().size();
        boolean hasMore = !budget.isLastLineTruncated() && nextOffset < total;

        AdapterResult result = AdapterResult.of(budget.getContent(), budget.getLines().size(),
                nextOffset, hasMore);
        result.setTruncated(budget.isTruncated());
        result.setResolvedPath(describe(files));
        if (headTruncated) {
            result.setMessage("部分日志文件超过 max-file-bytes，仅读取末尾内容，更早内容未纳入本次结果");
        }
        if (!missingDirs.isEmpty()) {
            String note = "部分日志目录不存在: " + String.join(", ", missingDirs);
            result.setMessage(result.getMessage() == null ? note : result.getMessage() + "；" + note);
        }
        return result;
    }

    private String emptyReason(ComponentRole role, LogKind kind,
                               LogAccessProperties.Options options, List<String> missingDirs) {
        if (!missingDirs.isEmpty()) {
            return "Flink 日志目录不存在: " + String.join(", ", missingDirs);
        }
        if (role == ComponentRole.JOB_MANAGER || role == ComponentRole.GENERIC) {
            if (options.getJobManagerLogDirs() == null || options.getJobManagerLogDirs().isEmpty()) {
                return "未配置 jobmanager-log-dirs，无法读取 JobManager 的 " + kind.wireName() + " 日志";
            }
        }
        if (role == ComponentRole.TASK_MANAGER || role == ComponentRole.GENERIC) {
            if (options.getTaskManagerLogDirs() == null || options.getTaskManagerLogDirs().isEmpty()) {
                return "未配置 taskmanager-log-dirs，无法读取 TaskManager 的 " + kind.wireName() + " 日志";
            }
        }
        String pattern = kind == LogKind.RUNTIME
                ? (role == ComponentRole.TASK_MANAGER
                ? options.getTaskManagerLogPattern() : options.getJobManagerLogPattern())
                : (role == ComponentRole.TASK_MANAGER
                ? options.getTaskManagerStdoutPattern() : options.getJobManagerStdoutPattern());
        return "在配置的目录中未匹配到 " + pattern + " 文件";
    }

    private List<Path> collect(List<String> dirs, String pattern, List<String> missingDirs) {
        List<Path> files = new ArrayList<>();
        if (dirs == null || dirs.isEmpty()) {
            return files;
        }
        for (String dir : dirs) {
            if (dir == null || dir.isBlank()) {
                continue;
            }
            Path path = Paths.get(dir.trim());
            if (!Files.isDirectory(path)) {
                missingDirs.add(dir);
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path, pattern)) {
                for (Path file : stream) {
                    if (Files.isRegularFile(file)) {
                        files.add(file);
                    }
                }
            } catch (IOException e) {
                missingDirs.add(dir + "（无法读取）");
            }
        }
        files.sort(Comparator.comparingLong(this::modifiedAtMillis).reversed());
        return files.size() > MAX_FILES ? new ArrayList<>(files.subList(0, MAX_FILES)) : files;
    }

    private List<Entry> parse(Path file, String content, LocalDateTime fallback) {
        List<Entry> entries = new ArrayList<>();
        String fileName = file.getFileName().toString();
        Entry current = null;
        String[] rawLines = content.split("\n", -1);
        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i];
            if (line.isEmpty() && (current == null || i == rawLines.length - 1)) {
                // 跳过文件头空行与末尾换行产生的空行
                continue;
            }
            LocalDateTime timestamp = parseTimestamp(line);
            if (timestamp != null) {
                current = new Entry(timestamp, fileName);
                entries.add(current);
            } else if (current == null) {
                current = new Entry(fallback, fileName);
                entries.add(current);
            }
            current.lines.add(line);
        }
        return entries;
    }

    /** 解析行首 {@code yyyy-MM-dd HH:mm:ss,SSS} / {@code ...SSS} 时间戳 */
    static LocalDateTime parseTimestamp(String line) {
        if (line == null || line.length() < 23) {
            return null;
        }
        try {
            if (line.charAt(4) != '-' || line.charAt(7) != '-' || line.charAt(10) != ' '
                    || line.charAt(13) != ':' || line.charAt(16) != ':') {
                return null;
            }
            char separator = line.charAt(19);
            if (separator != ',' && separator != '.') {
                return null;
            }
            LocalDate date = LocalDate.of(
                    Integer.parseInt(line.substring(0, 4)),
                    Integer.parseInt(line.substring(5, 7)),
                    Integer.parseInt(line.substring(8, 10)));
            LocalTime time = LocalTime.of(
                    Integer.parseInt(line.substring(11, 13)),
                    Integer.parseInt(line.substring(14, 16)),
                    Integer.parseInt(line.substring(17, 19)),
                    Integer.parseInt(line.substring(20, 23)) * 1_000_000);
            return LocalDateTime.of(date, time);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ReadResult readTail(Path file, long maxBytes) throws IOException {
        long size = Files.size(file);
        long from = Math.max(0, size - maxBytes);
        boolean truncatedHead = from > 0;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(from);
            if (truncatedHead) {
                // 丢弃第一行（可能被截断的半行）
                raf.readLine();
            }
            long remaining = size - raf.getFilePointer();
            if (remaining <= 0) {
                return new ReadResult("", truncatedHead);
            }
            byte[] data = new byte[(int) Math.min(remaining, Integer.MAX_VALUE)];
            raf.readFully(data);
            return new ReadResult(new String(data, StandardCharsets.UTF_8), truncatedHead);
        }
    }

    private LocalDateTime modifiedAt(Path file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
            return LocalDateTime.ofInstant(attrs.lastModifiedTime().toInstant(), ZoneId.systemDefault());
        } catch (IOException e) {
            return LocalDateTime.now();
        }
    }

    private long modifiedAtMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private boolean inRange(LocalDateTime timestamp, LocalDateTime start, LocalDateTime end) {
        if (timestamp == null || (start == null && end == null)) {
            return true;
        }
        if (start != null && timestamp.isBefore(start)) {
            return false;
        }
        return end == null || !timestamp.isAfter(end);
    }

    private String describe(List<Path> files) {
        if (files.isEmpty()) {
            return null;
        }
        String first = files.get(0).toString();
        return files.size() == 1 ? first : first + " 等 " + files.size() + " 个文件";
    }

    private static final class ReadResult {
        private final String content;
        private final boolean truncatedHead;

        private ReadResult(String content, boolean truncatedHead) {
            this.content = content;
            this.truncatedHead = truncatedHead;
        }
    }

    private static final class Entry {
        private final LocalDateTime timestamp;
        private final String fileName;
        private final List<String> lines = new ArrayList<>();

        private Entry(LocalDateTime timestamp, String fileName) {
            this.timestamp = timestamp;
            this.fileName = fileName;
        }

        private LocalDateTime getTimestamp() {
            return timestamp;
        }

        private String getFileName() {
            return fileName;
        }
    }
}
