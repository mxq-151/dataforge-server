package com.bigdata.modules.telemetry.service.metadata.spi;

import java.util.List;
import java.util.Map;
import com.bigdata.modules.telemetry.service.metadata.support.JdbcMetadataClient;

/**
 * 只读 SQL 行集客户端抽象。
 *
 * <p>生产实现是 {@link JdbcMetadataClient}（独立连接池 + 白名单）；测试可注入录制行集，
 * 避免为了单测引入 MySQL 兼容库。</p>
 */
public interface MetadataSqlClient {

    List<Map<String, String>> query(JdbcMetadataClient.ReadOnlyStatement statement);

    void close();
}
