package com.bigdata.modules.logs.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.logs.adapter.BudgetLimiter;
import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.dto.LogChainRequestDTO;
import com.bigdata.modules.logs.dto.LogQueryDTO;
import com.bigdata.modules.logs.vo.LogChainResultVO;
import com.bigdata.modules.logs.vo.LogQueryResultVO;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * 跨服务串联查询：典型链路是「DS 任务日志 → YARN AM 聚合日志 → Spark driver 日志」。
 *
 * <p>串联是诊断场景的核心诉求，因此单段失败不整体失败：每段记录状态与原因，
 * 后续段继续查。总预算由配置限制（默认 3 段 × 200KB），超出部分标记
 * {@code skipped_budget} / 截断，避免一次诊断把源服务打满。</p>
 */
@Service
public class LogChainService {

    private final LogAccessProperties properties;
    private final LogQueryService queryService;

    public LogChainService(LogAccessProperties properties, LogQueryService queryService) {
        this.properties = properties;
        this.queryService = queryService;
    }

    public LogChainResultVO chain(LogChainRequestDTO request) {
        if (request == null || request.getSegments() == null || request.getSegments().isEmpty()) {
            throw new BusinessException(400, "segments 不能为空");
        }
        int maxSegments = Math.max(properties.getChain().getMaxSegments(), 1);
        if (request.getSegments().size() > maxSegments) {
            throw new BusinessException(400, "最多串联 " + maxSegments + " 段日志");
        }

        int segmentBudget = Math.max(1024,
                Math.min(request.getMaxBytes() == null ? properties.getChain().getSegmentMaxBytes()
                        : request.getMaxBytes(), properties.getChain().getSegmentMaxBytes()));
        int totalBudget = Math.max(segmentBudget, properties.getChain().getTotalMaxBytes());

        LogChainResultVO response = new LogChainResultVO();
        response.setSegments(new ArrayList<>());
        int totalBytes = 0;
        boolean anyTruncated = false;

        for (int index = 0; index < request.getSegments().size(); index++) {
            LogChainRequestDTO.Segment segment = request.getSegments().get(index);
            LogChainResultVO.SegmentVO vo = new LogChainResultVO.SegmentVO();
            vo.setIndex(index);
            if (segment == null) {
                vo.setStatus("error");
                vo.setMessage("该段配置为空");
                response.getSegments().add(vo);
                continue;
            }
            vo.setServiceType(firstNonBlank(segment.getServiceType(), request.getServiceType()));
            vo.setComponentRole(segment.getComponentRole());
            vo.setLogKind(segment.getLogKind());

            int remaining = totalBudget - totalBytes;
            if (remaining <= 0) {
                vo.setStatus("skipped_budget");
                vo.setMessage("串联总预算（" + totalBudget + " 字节）已用尽，该段未查询");
                response.getSegments().add(vo);
                continue;
            }
            int segmentLimit = Math.min(segmentBudget, remaining);

            LogQueryDTO dto = new LogQueryDTO();
            dto.setSourceId(firstNonBlank(segment.getSourceId(), request.getSourceId()));
            dto.setServiceType(firstNonBlank(segment.getServiceType(), request.getServiceType()));
            dto.setCluster(firstNonBlank(segment.getCluster(), request.getCluster()));
            dto.setComponentRole(segment.getComponentRole());
            dto.setLogKind(segment.getLogKind());
            dto.setRef(segment.getRef());
            dto.setKeyword(segment.getKeyword());
            dto.setLimit(properties.getQuery().getMaxLines());
            dto.setMaxBytes(segmentLimit);

            try {
                LogQueryResultVO result = queryService.query(dto);
                vo.setSourceId(result.getSourceId());
                vo.setServiceType(result.getServiceType());
                vo.setComponentRole(result.getComponentRole());
                vo.setLogKind(result.getLogKind());
                vo.setDegraded(result.isDegraded());
                vo.setErrorCode(result.getErrorCode());
                vo.setMessage(result.getMessage());

                String content = result.getContent() == null ? "" : result.getContent();
                int bytes = content.getBytes(StandardCharsets.UTF_8).length;
                if (bytes > segmentLimit) {
                    content = BudgetLimiter.truncateUtf8(content, segmentLimit);
                    bytes = content.getBytes(StandardCharsets.UTF_8).length;
                    anyTruncated = true;
                    vo.setMessage(vo.getMessage() == null
                            ? "该段已按串联预算截断" : vo.getMessage() + "；该段已按串联预算截断");
                }
                vo.setContent(content);
                vo.setLineCount(BudgetLimiter.splitLines(content).size());
                vo.setByteSize(bytes);
                vo.setStatus(result.isDegraded() ? "degraded" : "ok");
                totalBytes += bytes;
            } catch (BusinessException e) {
                vo.setDegraded(true);
                vo.setErrorCode("BUSINESS_" + e.getCode());
                vo.setMessage(e.getMessage());
                vo.setStatus(statusOf(e.getCode()));
            }
            response.getSegments().add(vo);
        }

        response.setTotalBytes(totalBytes);
        response.setTruncated(anyTruncated);
        return response;
    }

    private String statusOf(int code) {
        if (code == 403) {
            return "denied";
        }
        if (code == 400 || code == 404) {
            return "disabled";
        }
        if (code == 429 || code == 503 || code == 504) {
            return "busy";
        }
        return "error";
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
