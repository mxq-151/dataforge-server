package com.bigdata.modules.telemetry.v1.metadata.provider.starrocks;

import com.bigdata.modules.telemetry.shared.SecretResolver;
import com.bigdata.modules.telemetry.v1.metadata.provider.jdbc.AbstractFeJdbcMetadataProvider;
import com.bigdata.modules.telemetry.v1.metadata.support.MetadataSqlClient;
import com.bigdata.modules.telemetry.v1.metadata.support.ProviderSettings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * StarRocks FE provider（MySQL 协议）。
 *
 * <p><b>未对真实实例验证</b>：环境未部署 StarRocks，语句沿用与 Doris 同源的
 * {@code SHOW FRONTENDS} / {@code SHOW BACKENDS} / {@code ADMIN SHOW FRONTEND CONFIG}，
 * 仅有 fixture 单测。首次接入真实实例时应先核对列名与语句兼容性，不要据印象调参。</p>
 */
@Component
public class StarRocksMetadataProvider extends AbstractFeJdbcMetadataProvider {

    public static final String PROVIDER_ID = "starrocks-fe";

    @Autowired
    public StarRocksMetadataProvider(ProviderSettings settings, SecretResolver secretResolver) {
        super(settings, secretResolver, PROVIDER_ID, "starrocks-mysql", "starrocks",
                "starrocks.fe", "starrocks.be", "StarRocks", "starrocks", 40);
    }

    /** 单测注入录制行集，避免为 StarRocks 引入 MySQL 兼容测试库。 */
    StarRocksMetadataProvider(ProviderSettings settings, MetadataSqlClient client) {
        super(settings, null, PROVIDER_ID, "starrocks-mysql", "starrocks",
                "starrocks.fe", "starrocks.be", "StarRocks", "starrocks", 40, client);
    }
}
