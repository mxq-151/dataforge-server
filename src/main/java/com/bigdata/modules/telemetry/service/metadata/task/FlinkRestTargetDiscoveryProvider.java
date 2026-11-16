package com.bigdata.modules.telemetry.service.metadata.task;

import com.bigdata.modules.telemetry.common.model.CapabilityCode;
import com.bigdata.modules.telemetry.common.model.Requirement;
import com.bigdata.modules.telemetry.common.model.RequirementKind;
import com.bigdata.modules.telemetry.common.http.SecretResolver;
import com.bigdata.modules.telemetry.common.http.EngineRestClient;
import com.bigdata.modules.telemetry.config.TelemetryBackendProperties;
import com.bigdata.modules.telemetry.service.metadata.model.RawTarget;
import com.bigdata.modules.telemetry.service.metadata.spi.TargetDiscoveryProvider;
import com.bigdata.modules.telemetry.service.metadata.model.TargetDiscoveryQuery;
import com.bigdata.modules.telemetry.service.metadata.support.TargetDiscoverySupport;
import com.bigdata.modules.telemetry.common.exception.BackendException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.bigdata.modules.telemetry.config.properties.Auth;
import com.bigdata.modules.telemetry.config.properties.FlinkRestConfig;
import com.bigdata.modules.telemetry.common.http.AuthorizationHeader;

/**
 * Flink REST 作业目标发现：复用第一个配置了 base-url 的 {@code telemetry.backends.flink-rest[]}，
 * 读 {@code /jobs/overview}，返回 jobId / name / state。
 */
@Component
public class FlinkRestTargetDiscoveryProvider implements TargetDiscoveryProvider {

    public static final String PROVIDER_ID = "flink-rest";
    private static final long MAX_RESPONSE_BYTES = 8L * 1024 * 1024;

    private final TelemetryBackendProperties properties;
    private final SecretResolver secretResolver;
    private final ObjectMapper objectMapper;

    public FlinkRestTargetDiscoveryProvider(TelemetryBackendProperties properties,
                                            SecretResolver secretResolver,
                                            ObjectMapper objectMapper) {
        this.properties = properties;
        this.secretResolver = secretResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public Set<String> serviceTypes() {
        return Set.of("flink");
    }

    @Override
    public Set<String> componentIds() {
        return Set.of("flink.jobmanager");
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean enabled() {
        return config() != null;
    }

    @Override
    public List<RawTarget> discover(TargetDiscoveryQuery query) {
        FlinkRestConfig config = config();
        if (config == null) {
            throw new BackendException(CapabilityCode.NOT_CONFIGURED,
                    "未配置 Flink REST 地址",
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.flink-rest[].base-url",
                            "配置 Flink JobManager REST 地址")));
        }
        EngineRestClient client = new EngineRestClient(config.getBaseUrl(),
                authorization(config.getAuth(), config.getId()),
                query.timeout() == null ? Duration.ofSeconds(10) : query.timeout(),
                MAX_RESPONSE_BYTES, objectMapper);
        JsonNode root = client.getJson("/jobs/overview");
        JsonNode jobs = root.path("jobs");
        if (!jobs.isArray()) {
            throw new BackendException(CapabilityCode.PARSE_ERROR,
                    "Flink /jobs/overview 响应缺少 jobs 数组");
        }
        List<RawTarget> targets = new ArrayList<>();
        for (JsonNode job : jobs) {
            String jobId = job.path("jid").asText("");
            if (jobId.isBlank()) {
                continue;
            }
            targets.add(new RawTarget("flink", "flink.jobmanager",
                    Map.of("jobId", jobId),
                    job.path("name").asText(jobId),
                    job.path("state").asText("UNKNOWN")));
        }
        return TargetDiscoverySupport.filter(targets, query);
    }

    private FlinkRestConfig config() {
        for (FlinkRestConfig config : properties.getFlinkRest()) {
            if (config != null && config.getBaseUrl() != null && !config.getBaseUrl().isBlank()) {
                return config;
            }
        }
        return null;
    }

    private String authorization(Auth auth, String id) {
        try {
            return AuthorizationHeader.of(auth, secretResolver, "flink-rest." + id);
        } catch (IllegalArgumentException e) {
            throw new BackendException(CapabilityCode.NOT_CONFIGURED,
                    "未知的 Flink REST 认证类型: " + auth.getType(),
                    List.of(new Requirement(RequirementKind.CONFIG,
                            "telemetry.backends.flink-rest[].auth.type",
                            "只支持 none / basic / bearer")));
        }
    }
}
