package com.bigdata.modules.asset.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.asset.vo.AssetProducerVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bigdata.modules.metadata.entity.TableMetadata;
import com.bigdata.modules.metadata.mapper.TableMetadataMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 产出任务代理 —— 查「这张表是由哪个调度工作流产出的」。
 *
 * <h3>为什么不落服务端副本</h3>
 * <p>调度系统的状态（上线/下线、最近一次运行成功还是失败）时刻在变。
 * 如果同步一份副本到本地，用户看到的永远是上一次同步时的状态 ——
 * 这种「看起来有数据、其实过期」的展示比空态更有害。因此这里只做实时代理查询。</p>
 *
 * <h3>未接入时的行为</h3>
 * <p>调度系统地址通过 {@code scheduler.dolphinscheduler.*} 配置。
 * 未配置时<b>返回空列表</b>而不是报错，也不返回任何占位数据：
 * 资产详情页会把「产出任务」页签渲染成空态。刻意不造假数据 ——
 * 一个假的任务名会让人以为这条血缘是通的。</p>
 *
 * <h3>调用策略</h3>
 * <p>两次请求：按表名检索工作流定义，再按表名检索最近的流程实例，
 * 在内存里按工作流定义 code 关联出最近一次运行状态。
 * 不逐工作流去查实例，避免「一张热表关联几十个工作流」时把上游打爆。</p>
 *
 * @author dataforge
 */
@Service
public class AssetProducerService {

    private static final Logger log = LoggerFactory.getLogger(AssetProducerService.class);

    /** 单次请求超时：调度系统是外部系统，不能让资产详情页被它拖死 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** 检索结果上限 */
    private static final int PAGE_SIZE = 50;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${scheduler.dolphinscheduler.base-url:}")
    private String baseUrl;

    @Value("${scheduler.dolphinscheduler.token:}")
    private String token;

    @Value("${scheduler.dolphinscheduler.default-project-code:}")
    private String defaultProjectCode;

    @Autowired
    private TableMetadataMapper tableMetadataMapper;

    @Autowired
    private AssetPermissionSupport permissionSupport;

    /** 该表的产出工作流列表；未接入调度系统时返回空列表 */
    public List<AssetProducerVO> producers(Long tableId) {
        if (tableId == null) {
            throw new BusinessException("tableId 不能为空");
        }
        TableMetadata table = tableMetadataMapper.selectById(tableId);
        if (table == null || (table.getDeleted() != null && table.getDeleted() == 1)) {
            throw new BusinessException(404, "资产不存在");
        }
        permissionSupport.checkSelectable(table);

        if (isBlank(baseUrl)) {
            log.debug("调度系统未配置（scheduler.dolphinscheduler.base-url），产出任务返回空列表");
            return List.of();
        }
        String projectCode = isBlank(defaultProjectCode) ? "1" : defaultProjectCode.trim();
        String keyword = table.getTableName();
        try {
            List<AssetProducerVO> definitions = queryDefinitions(projectCode, keyword);
            enrichWithLatestRun(projectCode, keyword, definitions);
            return definitions;
        } catch (Exception e) {
            // 外部依赖不可用不应让整个详情页失败：产出任务页签降级为空态并留日志
            log.warn("查询调度系统产出任务失败，table={}.{}, 原因={}",
                    table.getDatabaseName(), table.getTableName(), e.getMessage());
            return List.of();
        }
    }

    private List<AssetProducerVO> queryDefinitions(String projectCode, String keyword) throws Exception {
        String url = trimTrailingSlash(baseUrl) + "/dolphinscheduler/projects/" + projectCode
                + "/process-definition/list?pageNo=1&pageSize=" + PAGE_SIZE
                + "&searchVal=" + encode(keyword);
        JsonNode root = get(url);
        JsonNode list = root.path("data").path("totalList");
        List<AssetProducerVO> result = new ArrayList<>();
        if (!list.isArray()) {
            return result;
        }
        for (JsonNode node : list) {
            AssetProducerVO vo = new AssetProducerVO();
            vo.setProjectCode(projectCode);
            vo.setWorkflowCode(node.path("code").isMissingNode() ? null : node.path("code").asLong());
            vo.setWorkflowName(text(node, "name"));
            vo.setNodeName(text(node, "name"));
            vo.setOwnerName(firstNonBlank(text(node, "userName"), text(node, "createUser")));
            vo.setScheduleCycle(firstNonBlank(text(node, "releaseState"), ""));
            result.add(vo);
        }
        return result;
    }

    /** 用最近的流程实例补齐「最近一次运行状态 / 时间」 */
    private void enrichWithLatestRun(String projectCode, String keyword, List<AssetProducerVO> definitions)
            throws Exception {
        if (definitions.isEmpty()) {
            return;
        }
        Map<Long, AssetProducerVO> byCode = new HashMap<>();
        for (AssetProducerVO vo : definitions) {
            if (vo.getWorkflowCode() != null) {
                byCode.put(vo.getWorkflowCode(), vo);
            }
        }
        String url = trimTrailingSlash(baseUrl) + "/dolphinscheduler/projects/" + projectCode
                + "/process-instances?pageNo=1&pageSize=" + PAGE_SIZE
                + "&searchVal=" + encode(keyword);
        JsonNode root = get(url);
        JsonNode list = root.path("data").path("totalList");
        if (!list.isArray()) {
            return;
        }
        for (JsonNode node : list) {
            long definitionCode = node.path("processDefinitionCode").asLong();
            AssetProducerVO vo = byCode.get(definitionCode);
            if (vo == null) {
                continue;
            }
            // 实例按开始时间倒序返回，第一条即最近一次运行
            if (isBlank(vo.getLastRunState())) {
                vo.setLastRunState(text(node, "state"));
                vo.setLastRunTime(firstNonBlank(text(node, "startTime"), text(node, "endTime")));
            }
            // 实例上的 owner 比定义上的创建人更贴近「现在谁在维护」
            String owner = firstNonBlank(text(node, "userName"), text(node, "executorName"));
            if (!isBlank(owner)) {
                vo.setOwnerName(owner);
            }
        }
    }

    private JsonNode get(String url) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        if (!isBlank(token)) {
            builder.header("token", token.trim());
        }
        HttpResponse<String> response = httpClient.send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("调度系统返回 HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException("调度系统返回业务错误 code=" + code
                    + ", msg=" + root.path("msg").asText(""));
        }
        return root;
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimTrailingSlash(String url) {
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
