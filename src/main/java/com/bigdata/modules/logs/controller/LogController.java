package com.bigdata.modules.logs.controller;

import com.bigdata.common.vo.Result;
import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.dto.LogChainRequestDTO;
import com.bigdata.modules.logs.dto.LogQueryDTO;
import com.bigdata.modules.logs.model.LogArtifact;
import com.bigdata.modules.logs.service.LogChainService;
import com.bigdata.modules.logs.service.LogExecutorRegistry;
import com.bigdata.modules.logs.service.LogQueryService;
import com.bigdata.modules.logs.vo.LogChainResultVO;
import com.bigdata.modules.logs.vo.LogQueryResultVO;
import com.bigdata.modules.logs.vo.LogSourceVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一日志访问层对外接口。
 *
 * <p>服务端 context-path 为 {@code /api}，因此对外地址是 {@code /api/logs/**}。
 * 所有日志都必须经由此处获取，调用方不直连 DS / YARN / Spark 等服务的日志文件或接口。</p>
 */
@RestController
@RequestMapping("/logs")
public class LogController {

    private static final Logger log = LoggerFactory.getLogger(LogController.class);

    private final LogQueryService queryService;
    private final LogChainService chainService;
    private final LogAccessProperties properties;
    private final LogExecutorRegistry executorRegistry;

    public LogController(LogQueryService queryService, LogChainService chainService,
                         LogAccessProperties properties, LogExecutorRegistry executorRegistry) {
        this.queryService = queryService;
        this.chainService = chainService;
        this.properties = properties;
        this.executorRegistry = executorRegistry;
    }

    /** 已配置的日志源；未配置地址的源返回 enabled=false 与原因 */
    @GetMapping("/sources")
    public Result<List<LogSourceVO>> sources() {
        return Result.success(queryService.listSources());
    }

    /** 统一检索 */
    @PostMapping("/query")
    public Result<LogQueryResultVO> query(@RequestBody LogQueryDTO dto) {
        return Result.success(queryService.query(dto));
    }

    /**
     * 流式检索（SSE）：按游标逐页推送 {@code page} 事件，最后推送 {@code done}。
     * <p>受限：最多 {@code logs.stream.max-pages} 页、总时长 {@code logs.stream.timeout-ms}。</p>
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String sourceId,
                             @RequestParam(required = false) String serviceType,
                             @RequestParam(required = false) String cluster,
                             @RequestParam(required = false) String componentRole,
                             @RequestParam(required = false) String logKind,
                             @RequestParam(required = false) String cursor,
                             @RequestParam(required = false) Integer limit,
                             @RequestParam(required = false) Integer maxBytes,
                             @RequestParam(required = false) String keyword,
                             @RequestParam(required = false) String taskInstanceId,
                             @RequestParam(required = false) String applicationId,
                             @RequestParam(required = false) String containerId,
                             @RequestParam(required = false) String appId,
                             @RequestParam(required = false) String path,
                             @RequestParam(required = false) String host,
                             @RequestParam(required = false) String user) {

        long timeoutMs = properties.getStream().getTimeoutMs();
        SseEmitter emitter = new SseEmitter(timeoutMs);
        Map<String, String> ref = new LinkedHashMap<>();
        putIfPresent(ref, "taskInstanceId", taskInstanceId);
        putIfPresent(ref, "applicationId", applicationId);
        putIfPresent(ref, "containerId", containerId);
        putIfPresent(ref, "appId", appId);
        putIfPresent(ref, "path", path);
        putIfPresent(ref, "host", host);
        putIfPresent(ref, "user", user);

        LogQueryDTO base = new LogQueryDTO();
        base.setSourceId(sourceId);
        base.setServiceType(serviceType);
        base.setCluster(cluster);
        base.setComponentRole(componentRole);
        base.setLogKind(logKind);
        base.setRef(ref);
        base.setKeyword(keyword);
        base.setLimit(limit);
        base.setMaxBytes(maxBytes);

        executorRegistry.get("stream").execute(() -> {
            long deadline = System.currentTimeMillis() + Math.max(timeoutMs - 2000, 1000);
            int pages = 0;
            String pageCursor = cursor;
            try {
                while (pages < Math.max(properties.getStream().getMaxPages(), 1)
                        && System.currentTimeMillis() < deadline) {
                    LogQueryDTO pageRequest = copy(base);
                    pageRequest.setCursor(pageCursor);
                    LogQueryResultVO result = queryService.query(pageRequest);
                    emitter.send(SseEmitter.event().name("page")
                            .data(result, MediaType.APPLICATION_JSON));
                    pages++;
                    if (result.isDegraded() || !result.isHasMore() || result.getNextCursor() == null) {
                        break;
                    }
                    pageCursor = result.getNextCursor();
                }
                Map<String, Object> done = new HashMap<>();
                done.put("pages", pages);
                emitter.send(SseEmitter.event().name("done").data(done, MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (Exception e) {
                log.debug("日志流式推送结束: {}", e.getMessage());
                try {
                    Map<String, Object> error = new HashMap<>();
                    error.put("message", e.getMessage() == null ? "日志流式推送失败" : e.getMessage());
                    emitter.send(SseEmitter.event().name("error").data(error, MediaType.APPLICATION_JSON));
                } catch (Exception ignored) {
                    // 客户端已断开
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /** 列举产物（file 适配器支持；其他源返回明确错误） */
    @GetMapping("/artifacts")
    public Result<List<LogArtifact>> artifacts(@RequestParam(required = false) String sourceId,
                                               @RequestParam(required = false) String serviceType,
                                               @RequestParam(required = false) String cluster,
                                               @RequestParam(required = false) String path) {
        LogQueryDTO dto = new LogQueryDTO();
        dto.setSourceId(sourceId);
        dto.setServiceType(serviceType);
        dto.setCluster(cluster);
        Map<String, String> ref = new LinkedHashMap<>();
        putIfPresent(ref, "path", path);
        dto.setRef(ref);
        return Result.success(queryService.listArtifacts(dto));
    }

    /** 下载产物：文本内容会先脱敏，大小受 logs.sources[].options.max-download-bytes 限制 */
    @GetMapping("/artifacts/content")
    public ResponseEntity<byte[]> artifactContent(@RequestParam(required = false) String sourceId,
                                                  @RequestParam(required = false) String serviceType,
                                                  @RequestParam(required = false) String cluster,
                                                  @RequestParam String artifactPath) {
        LogQueryDTO dto = new LogQueryDTO();
        dto.setSourceId(sourceId);
        dto.setServiceType(serviceType);
        dto.setCluster(cluster);
        byte[] content = queryService.fetchArtifact(dto, artifactPath);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + fileName(artifactPath) + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(content);
    }

    /** 跨服务串联：DS 任务日志 → YARN 聚合日志 → Spark driver 日志 */
    @PostMapping("/chain")
    public Result<LogChainResultVO> chain(@RequestBody LogChainRequestDTO request) {
        return Result.success(chainService.chain(request));
    }

    private LogQueryDTO copy(LogQueryDTO source) {
        LogQueryDTO dto = new LogQueryDTO();
        dto.setSourceId(source.getSourceId());
        dto.setServiceType(source.getServiceType());
        dto.setCluster(source.getCluster());
        dto.setComponentRole(source.getComponentRole());
        dto.setLogKind(source.getLogKind());
        dto.setRef(source.getRef());
        dto.setTimeRange(source.getTimeRange());
        dto.setKeyword(source.getKeyword());
        dto.setLimit(source.getLimit());
        dto.setMaxBytes(source.getMaxBytes());
        return dto;
    }

    private void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "artifact.log";
        }
        String normalized = path.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1);
        return name.isBlank() ? "artifact.log" : name;
    }
}
