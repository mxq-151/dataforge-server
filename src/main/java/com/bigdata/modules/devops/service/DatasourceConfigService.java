package com.bigdata.modules.devops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.devops.dto.DatasourceConfigDTO;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.ConnectionType;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.devops.vo.ConnectionTestResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;

import com.zaxxer.hikari.HikariDataSource;

@Service
public class DatasourceConfigService {

    private static final Logger logger = LoggerFactory.getLogger(DatasourceConfigService.class);

    private static final String KYUUBI_DRIVER = "org.apache.kyuubi.jdbc.KyuubiHiveDriver";
    private static final String MYSQL_DRIVER = "com.mysql.cj.jdbc.Driver";

    static {
        loadDriverSilently(KYUUBI_DRIVER);
        loadDriverSilently(MYSQL_DRIVER);
    }

    private static void loadDriverSilently(String driverClass) {
        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException ignored) {
        }
    }

    @Autowired
    private DatasourceConfigMapper datasourceConfigMapper;

    @Autowired
    private DatasourceConnectionPoolManager poolManager;

    /**
     * 解析连接方式：若未指定则根据数据源类型推断默认值（向后兼容）
     */
    public static ConnectionType resolveConnectionType(DatasourceConfig config) {
        if (config.getConnectionType() != null) {
            return config.getConnectionType();
        }
        return inferDefaultConnectionType(config.getDatasourceType());
    }

    public static ConnectionType resolveConnectionType(DatasourceConfigDTO dto) {
        if (dto.getConnectionType() != null) {
            return dto.getConnectionType();
        }
        return inferDefaultConnectionType(dto.getDatasourceType());
    }

    /**
     * 根据数据源类型推断默认连接方式
     */
    private static ConnectionType inferDefaultConnectionType(DatasourceType datasourceType) {
        if (datasourceType == null) {
            return ConnectionType.JDBC;
        }
        switch (datasourceType) {
            case ES:
                return ConnectionType.HTTP;
            default:
                return ConnectionType.JDBC;
        }
    }

    public Page<DatasourceConfig> getDatasourceList(int current, int size, String datasourceName, DatasourceType datasourceType) {
        Page<DatasourceConfig> page = new Page<>(current, size);
        LambdaQueryWrapper<DatasourceConfig> wrapper = new LambdaQueryWrapper<>();
        if (datasourceName != null && !datasourceName.isEmpty()) {
            wrapper.like(DatasourceConfig::getDatasourceName, datasourceName);
        }
        if (datasourceType != null) {
            wrapper.eq(DatasourceConfig::getDatasourceType, datasourceType);
        }
        wrapper.orderByDesc(DatasourceConfig::getCreateTime);
        return datasourceConfigMapper.selectPage(page, wrapper);
    }

    public List<DatasourceConfig> getAllDatasources() {
        LambdaQueryWrapper<DatasourceConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DatasourceConfig::getConnectionStatus, 1);
        wrapper.orderByAsc(DatasourceConfig::getId);
        return datasourceConfigMapper.selectList(wrapper);
    }

    public DatasourceConfig getDatasourceById(Long id) {
        return datasourceConfigMapper.selectById(id);
    }

    public DatasourceConfig getDatasourceByName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        LambdaQueryWrapper<DatasourceConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DatasourceConfig::getDatasourceName, name.trim());
        wrapper.last("LIMIT 1");
        return datasourceConfigMapper.selectOne(wrapper);
    }

    public void saveDatasource(DatasourceConfig config) {
        // 保存时若未指定连接方式，自动推断默认值
        if (config.getConnectionType() == null) {
            config.setConnectionType(inferDefaultConnectionType(config.getDatasourceType()));
        }
        config.setConnectionStatus(0);
        datasourceConfigMapper.insert(config);
    }

    public void updateDatasource(DatasourceConfig config) {
        // 更新时若未指定连接方式，自动推断默认值
        if (config.getConnectionType() == null) {
            config.setConnectionType(inferDefaultConnectionType(config.getDatasourceType()));
        }
        datasourceConfigMapper.updateById(config);
        poolManager.refreshPool(config.getId());
    }

    public void deleteDatasource(Long id) {
        DatasourceConfig config = datasourceConfigMapper.selectById(id);
        if (config == null) {
            throw new BusinessException("数据源不存在");
        }
        poolManager.closePool(id);
        datasourceConfigMapper.deleteById(id);
    }

    public ConnectionTestResult testConnection(Long id) {
        DatasourceConfig config = datasourceConfigMapper.selectById(id);
        if (config == null) {
            throw new BusinessException("数据源不存在");
        }

        boolean success = false;
        String message = "";
        long startTime = System.currentTimeMillis();
        ConnectionType connType = resolveConnectionType(config);

        try {
            switch (connType) {
                case HTTP:
                    success = testHttpConnection(config.getUrl(), config.getUsername(), config.getPassword());
                    message = success ? "HTTP连接成功" : "HTTP连接失败";
                    break;
                case ARROW:
                    success = testArrowConnection(config.getUrl(), config.getUsername(), config.getPassword());
                    message = success ? "Arrow Flight连接成功" : "Arrow Flight连接失败";
                    break;
                case JDBC:
                default:
                    success = poolManager.testConnection(id);
                    message = success ? config.getDatasourceType().getDescription() + "连接成功"
                            : config.getDatasourceType().getDescription() + "连接失败";
                    break;
            }
        } catch (Exception e) {
            logger.error("测试数据源连接失败: {}", e.getMessage());
            message = "连接异常: " + e.getMessage();
        }

        long costTime = System.currentTimeMillis() - startTime;

        config.setConnectionStatus(success ? 1 : 2);
        config.setLastTestTime(LocalDateTime.now());
        datasourceConfigMapper.updateById(config);

        return new ConnectionTestResult(success, message, costTime + "ms");
    }

    public ConnectionTestResult testConnectionByConfig(DatasourceConfigDTO dto) {
        boolean success = false;
        String message = "";
        long startTime = System.currentTimeMillis();
        ConnectionType connType = resolveConnectionType(dto);

        try {
            switch (connType) {
                case HTTP:
                    success = testHttpConnection(dto.getUrl(), dto.getUsername(), dto.getPassword());
                    message = success ? "HTTP连接成功" : "HTTP连接失败";
                    break;
                case ARROW:
                    success = testArrowConnection(dto.getUrl(), dto.getUsername(), dto.getPassword());
                    message = success ? "Arrow Flight连接成功" : "Arrow Flight连接失败";
                    break;
                case JDBC:
                default:
                    DatasourceConfig tempConfig = new DatasourceConfig();
                    tempConfig.setDatasourceType(dto.getDatasourceType());
                    tempConfig.setConnectionType(ConnectionType.JDBC);
                    tempConfig.setUrl(dto.getUrl());
                    tempConfig.setUsername(dto.getUsername());
                    tempConfig.setPassword(dto.getPassword());

                    Long tempId = -System.currentTimeMillis();
                    tempConfig.setId(tempId);

                    HikariDataSource tempPool = poolManager.createTempPool(tempConfig);
                    try {
                        success = poolManager.testPoolConnection(tempPool);
                        message = success ? dto.getDatasourceType().getDescription() + "连接成功"
                                : dto.getDatasourceType().getDescription() + "连接失败";
                    } finally {
                        tempPool.close();
                    }
                    break;
            }
        } catch (Exception e) {
            message = "连接异常: " + e.getMessage();
        }

        long costTime = System.currentTimeMillis() - startTime;
        return new ConnectionTestResult(success, message, costTime + "ms");
    }

    public Connection getConnection(Long datasourceId) throws java.sql.SQLException {
        return poolManager.getConnection(datasourceId);
    }

    public Connection getConnection(Long datasourceId, String databaseName) throws java.sql.SQLException {
        return poolManager.getConnection(datasourceId, databaseName);
    }

    /**
     * HTTP连接测试：发送GET请求检测服务可用性
     */
    private boolean testHttpConnection(String url, String username, String password) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (username != null && password != null) {
                String auth = username + ":" + password;
                String encoded = java.util.Base64.getEncoder().encodeToString(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                conn.setRequestProperty("Authorization", "Basic " + encoded);
            }
            int responseCode = conn.getResponseCode();
            return responseCode >= 200 && responseCode < 300;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Arrow Flight连接测试
     * 注意：完整实现需要添加 arrow-flight-core / arrow-memory-netty 依赖
     * 当前为基础连通性检测（TCP端口探测）
     */
    private boolean testArrowConnection(String url, String username, String password) {
        try {
            String host = extractHostFromUrl(url);
            int port = extractPortFromUrl(url);
            // 基础实现：通过Socket探测目标端口是否可达
            // Arrow Flight默认使用gRPC协议，默认端口8815
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress(host, port), 5000);
                return true;
            }
        } catch (Exception e) {
            logger.warn("Arrow Flight连接测试失败: url={}, error={}", url, e.getMessage());
            return false;
        }
    }

    private String extractHostFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return "localhost";
        }
        // 移除协议前缀
        String cleanUrl = url.replaceFirst("^https?://", "").replaceFirst("^grpc://", "").replaceFirst("^arrow://", "");
        int colonIndex = cleanUrl.indexOf(':');
        if (colonIndex > 0) {
            return cleanUrl.substring(0, colonIndex);
        }
        int slashIndex = cleanUrl.indexOf('/');
        if (slashIndex > 0) {
            return cleanUrl.substring(0, slashIndex);
        }
        return cleanUrl;
    }

    private int extractPortFromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return 8815; // Arrow Flight 默认端口
        }
        String cleanUrl = url.replaceFirst("^https?://", "").replaceFirst("^grpc://", "").replaceFirst("^arrow://", "");
        int colonIndex = cleanUrl.indexOf(':');
        if (colonIndex > 0) {
            String portStr = cleanUrl.substring(colonIndex + 1);
            int slashIndex = portStr.indexOf('/');
            if (slashIndex > 0) {
                portStr = portStr.substring(0, slashIndex);
            }
            try {
                return Integer.parseInt(portStr);
            } catch (NumberFormatException e) {
                return 8815;
            }
        }
        return 8815;
    }
}
