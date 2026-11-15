package com.bigdata.modules.telemetry.v1.metadata.provider.doris;

import com.bigdata.modules.telemetry.shared.SecretResolver;
import com.bigdata.modules.telemetry.v1.metadata.provider.jdbc.AbstractFeJdbcMetadataProvider;
import com.bigdata.modules.telemetry.v1.metadata.support.MetadataSqlClient;
import com.bigdata.modules.telemetry.v1.metadata.support.ProviderSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Doris FE provider（MySQL 协议，已对真实实例核实）。
 *
 * <p>版本来自 {@code SHOW FRONTENDS} / {@code SHOW BACKENDS} 的 {@code Version} 列；
 * 配置来自 {@code ADMIN SHOW FRONTEND CONFIG}。Doris 4.1.5 的 HTTP
 * {@code /api/bootstrap} 版本为空、{@code /api/show_frontends} 返回前端页面，
 * 因此 HTTP 路径不可用，必须走 MySQL 协议。</p>
 */
@Component
public class DorisMetadataProvider extends AbstractFeJdbcMetadataProvider {

    public static final String PROVIDER_ID = "doris-fe";

    @Autowired
    public DorisMetadataProvider(ProviderSettings settings, SecretResolver secretResolver) {
        super(settings, secretResolver, PROVIDER_ID, "doris-mysql", "doris",
                "doris.fe", "doris.be", "Apache Doris", "doris", 30);
    }

    /** 单测注入录制行集，避免为 Doris 引入 MySQL 兼容测试库。 */
    DorisMetadataProvider(ProviderSettings settings, MetadataSqlClient client) {
        super(settings, null, PROVIDER_ID, "doris-mysql", "doris",
                "doris.fe", "doris.be", "Apache Doris", "doris", 30, client);
    }
}
