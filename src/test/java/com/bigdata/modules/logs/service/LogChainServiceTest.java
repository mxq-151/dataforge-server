package com.bigdata.modules.logs.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.logs.LogTestSupport;
import com.bigdata.modules.logs.config.LogAccessProperties;
import com.bigdata.modules.logs.dto.LogChainRequestDTO;
import com.bigdata.modules.logs.dto.LogQueryDTO;
import com.bigdata.modules.logs.vo.LogChainResultVO;
import com.bigdata.modules.logs.vo.LogQueryResultVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("跨服务串联：单段失败不整体失败、预算截断、段数上限")
class LogChainServiceTest {

    @Test
    @DisplayName("三段均成功时按顺序返回")
    void threeSegmentsOk() {
        LogAccessProperties properties = LogTestSupport.properties();
        LogQueryService queryService = mock(LogQueryService.class);
        when(queryService.query(any())).thenAnswer(invocation -> result("hello"));
        LogChainService chainService = new LogChainService(properties, queryService);

        LogChainResultVO result = chainService.chain(request(3, "application_1"));

        assertEquals(3, result.getSegments().size());
        result.getSegments().forEach(segment -> assertEquals("ok", segment.getStatus()));
        assertEquals(15, result.getTotalBytes());
    }

    @Test
    @DisplayName("中间段未配置时标记 disabled，后续段继续查询")
    void middleSegmentFailureDoesNotStopOthers() {
        LogAccessProperties properties = LogTestSupport.properties();
        LogQueryService queryService = mock(LogQueryService.class);
        when(queryService.query(any())).thenAnswer(invocation -> {
            LogQueryDTO dto = invocation.getArgument(0);
            if (dto.getRef() != null && "bad".equals(dto.getRef().get("applicationId"))) {
                throw new BusinessException(404, "未配置 YARN 日志源");
            }
            return result("hello");
        });
        LogChainService chainService = new LogChainService(properties, queryService);

        LogChainRequestDTO request = request(3, "application_1");
        request.getSegments().get(1).setRef(Map.of("applicationId", "bad"));

        LogChainResultVO result = chainService.chain(request);

        assertEquals("ok", result.getSegments().get(0).getStatus());
        assertEquals("disabled", result.getSegments().get(1).getStatus());
        assertTrue(result.getSegments().get(1).getMessage().contains("未配置"));
        assertEquals("ok", result.getSegments().get(2).getStatus());
    }

    @Test
    @DisplayName("超过最大段数直接拒绝")
    void tooManySegments() {
        LogAccessProperties properties = LogTestSupport.properties();
        LogChainService chainService = new LogChainService(properties, mock(LogQueryService.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> chainService.chain(request(4, "application_1")));
        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("最多串联"));
    }

    @Test
    @DisplayName("总预算用尽时后续段标记 skipped_budget，且单段内容被截断")
    void budgetTruncation() {
        LogAccessProperties properties = LogTestSupport.properties();
        properties.getChain().setSegmentMaxBytes(1024);
        properties.getChain().setTotalMaxBytes(1300);
        LogQueryService queryService = mock(LogQueryService.class);
        when(queryService.query(any())).thenAnswer(invocation -> result("a".repeat(2000)));
        LogChainService chainService = new LogChainService(properties, queryService);

        LogChainResultVO result = chainService.chain(request(2, "application_1"));

        assertEquals(1024, result.getSegments().get(0).getByteSize());
        assertEquals(276, result.getSegments().get(1).getByteSize());
        assertEquals(1300, result.getTotalBytes());
        assertTrue(result.isTruncated());
    }

    private LogChainRequestDTO request(int segments, String applicationId) {
        LogChainRequestDTO request = new LogChainRequestDTO();
        request.setServiceType("ds");
        request.setCluster("default");
        List<LogChainRequestDTO.Segment> list = new ArrayList<>();
        for (int i = 0; i < segments; i++) {
            LogChainRequestDTO.Segment segment = new LogChainRequestDTO.Segment();
            segment.setComponentRole(i == 0 ? "worker" : "resource_manager");
            segment.setLogKind(i == 0 ? "runtime" : "aggregate");
            segment.setRef(i == 0 ? Map.of("taskInstanceId", "1") : Map.of("applicationId", applicationId));
            list.add(segment);
        }
        request.setSegments(list);
        return request;
    }

    private LogQueryResultVO result(String content) {
        LogQueryResultVO vo = new LogQueryResultVO();
        vo.setSourceId("ds-default");
        vo.setServiceType("ds");
        vo.setComponentRole("worker");
        vo.setLogKind("runtime");
        vo.setContent(content);
        vo.setLineCount(1);
        vo.setByteSize(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        return vo;
    }
}
