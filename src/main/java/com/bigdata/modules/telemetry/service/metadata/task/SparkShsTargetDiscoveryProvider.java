package com.bigdata.modules.telemetry.service.metadata.task;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.common.http.SecretResolver;
import com.bigdata.modules.telemetry.common.http.EngineRestClient;
import com.bigdata.modules.telemetry.service.metadata.model.RawTarget;
import com.bigdata.modules.telemetry.service.metadata.spi.TargetDiscoveryProvider;
import com.bigdata.modules.telemetry.service.metadata.model.TargetDiscoveryQuery;
import com.bigdata.modules.telemetry.service.metadata.support.ProviderSettings;
import com.bigdata.modules.telemetry.service.metadata.support.TargetDiscoverySupport;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Spark SHS 作业目标发现：读 {@code /api/v1/applications}，返回 applicationId / name / state。
 *
 * <p>同一份 {@code telemetry.metadata.providers.spark-shs} 配置同时服务 scope=config 与
 * scope=targets；未配置 base-url 时按 unavailable + requirements 返回，不静默空列表。</p>
 */
@Component
public class SparkShsTargetDiscoveryProvider implements TargetDiscoveryProvider {

    public static final String PROVIDER_ID = "spark-shs";
    private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    private final ProviderSettings settings;
    private final SecretResolver secretResolver;
    private final ObjectMapper objectMapper;

    public SparkShsTargetDiscoveryProvider(ProviderSettings settings,
                                           SecretResolver secretResolver,
                                           ObjectMapper objectMapper) {
        this.settings = settings;
        this.secretResolver = secretResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Set<String> serviceTypes() {
        return Set.of("spark");
    }

    @Override
    public Set<String> componentIds() {
        return Set.of("spark.driver");
    }

    @Override
    public int order() {
        return settings.intValue(PROVIDER_ID, "order", 30);
    }

    @Override
    public boolean enabled() {
        return settings.enabled(PROVIDER_ID);
    }

    @Override
    public List<RawTarget> discover(TargetDiscoveryQuery query) {
        String baseUrl = settings.required(PROVIDER_ID, "base-url");
        EngineRestClient client = new EngineRestClient(baseUrl, authorizationHeader(),
                query.timeout() == null ? Duration.ofSeconds(10) : query.timeout(),
                MAX_RESPONSE_BYTES, objectMapper);
        JsonNode root = client.getJson("/api/v1/applications");
        if (!root.isArray()) {
            throw new BackendException(CapabilityCode.PARSE_ERROR,
                    "SHS /api/v1/applications 响应不是数组");
        }
        List<RawTarget> targets = new ArrayList<>();
        for (JsonNode application : root) {
            String applicationId = application.path("id").asText("");
            if (applicationId.isBlank()) {
                continue;
            }
            targets.add(new RawTarget("spark", "spark.driver",
                    Map.of("applicationId", applicationId, "appId", applicationId),
                    application.path("name").asText(applicationId),
                    state(application.path("attempts"))));
        }
        return TargetDiscoverySupport.filter(targets, query);
    }

    private static String state(JsonNode attempts) {
        if (!attempts.isArray() || attempts.isEmpty()) {
            return "UNKNOWN";
        }
        JsonNode latest = null;
        int latestAttempt = -1;
        for (JsonNode attempt : attempts) {
            int attemptId = attempt.path("attemptId").asInt(0);
            if (latest == null || attemptId >= latestAttempt) {
                latest = attempt;
                latestAttempt = attemptId;
            }
        }
        return latest != null && latest.path("completed").asBoolean(false)
                ? "COMPLETED" : "RUNNING";
    }

    private String authorizationHeader() {
        Map<String, Object> auth = settings.map(PROVIDER_ID, "auth");
        if (auth.isEmpty() || "none".equalsIgnoreCase(String.valueOf(auth.get("type")))) {
            return null;
        }
        String type = String.valueOf(auth.get("type"));
        if ("basic".equalsIgnoreCase(type)) {
            String username = auth.get("username") == null ? "" : String.valueOf(auth.get("username"));
            String password = secretResolver.resolve(null,
                    auth.get("password-ref") == null ? null : String.valueOf(auth.get("password-ref")),
                    PROVIDER_ID + ".password");
            return "Basic " + Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        }
        if ("bearer".equalsIgnoreCase(type)) {
            String token = secretResolver.resolve(null,
                    auth.get("token-ref") == null ? null : String.valueOf(auth.get("token-ref")),
                    PROVIDER_ID + ".token");
            return "Bearer " + token;
        }
        throw new BackendException(CapabilityCode.NOT_CONFIGURED,
                "未知的 Spark SHS 认证类型: " + type,
                List.of(new Requirement(RequirementKind.CONFIG,
                        "telemetry.metadata.providers." + PROVIDER_ID + ".auth.type",
                        "只支持 none / basic / bearer")));
    }

}
