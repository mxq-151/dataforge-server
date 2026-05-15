package com.bigdata.modules.devops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.devops.dto.DatasourceConfigDTO;
import com.bigdata.modules.devops.entity.DatasourceConfig;
import com.bigdata.modules.devops.enums.DatasourceType;
import com.bigdata.modules.devops.mapper.DatasourceConfigMapper;
import com.bigdata.modules.devops.vo.ConnectionTestResult;
import com.bigdata.modules.metadata.vo.SqlQueryResultVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
        config.setConnectionStatus(0);
        datasourceConfigMapper.insert(config);
    }

    public void updateDatasource(DatasourceConfig config) {
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

        try {
            DatasourceType type = config.getDatasourceType();
            if (type == null) {
                message = "数据源类型为空";
            } else if (type == DatasourceType.ES) {
                success = testEsConnection(config.getUrl());
                message = success ? "Elasticsearch连接成功" : "Elasticsearch连接失败";
            } else {
                success = poolManager.testConnection(id);
                message = success ? type.getDescription() + "连接成功" : type.getDescription() + "连接失败";
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

        try {
            DatasourceType type = dto.getDatasourceType();
            if (type == null) {
                message = "数据源类型为空";
            } else if (type == DatasourceType.ES) {
                success = testEsConnection(dto.getUrl());
                message = success ? "Elasticsearch连接成功" : "Elasticsearch连接失败";
            } else {
                DatasourceConfig tempConfig = new DatasourceConfig();
                tempConfig.setDatasourceType(type);
                tempConfig.setUrl(dto.getUrl());
                tempConfig.setUsername(dto.getUsername());
                tempConfig.setPassword(dto.getPassword());

                Long tempId = -System.currentTimeMillis();
                tempConfig.setId(tempId);

                HikariDataSource tempPool = poolManager.createTempPool(tempConfig);
                try {
                    success = poolManager.testPoolConnection(tempPool);
                    message = success ? type.getDescription() + "连接成功" : type.getDescription() + "连接失败";
                } finally {
                    tempPool.close();
                }
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

    private boolean testEsConnection(String url) throws Exception {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int responseCode = conn.getResponseCode();
            return responseCode == 200;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public SqlQueryResultVO executeSqlQuery(Long datasourceId, String databaseName, String sql, Integer limit) {
        long startTime = System.currentTimeMillis();
        java.sql.Connection connection = null;
        java.sql.Statement stmt = null;
        java.sql.ResultSet rs = null;

        SqlQueryResultVO result = new SqlQueryResultVO();

        try {
            connection = getConnection(datasourceId, databaseName);
            stmt = connection.createStatement();

            boolean isQuery = stmt.execute(sql);
            List<String> columns = new java.util.ArrayList<>();
            List<List<Object>> rows = new java.util.ArrayList<>();
            int rowCount = 0;

            if (isQuery) {
                rs = stmt.getResultSet();
                java.sql.ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                for (int i = 1; i <= columnCount; i++) {
                    columns.add(metaData.getColumnLabel(i));
                }

                while (rs.next() && rowCount < limit) {
                    List<Object> row = new java.util.ArrayList<>();
                    for (int i = 1; i <= columnCount; i++) {
                        Object value = rs.getObject(i);
                        row.add(value);
                    }
                    rows.add(row);
                    rowCount++;
                }
            } else {
                int updateCount = stmt.getUpdateCount();
                columns.add("affected_rows");
                rows.add(List.of(updateCount));
                rowCount = updateCount;
            }

            result.setSuccess(true);
            result.setSqlType(detectSqlType(sql));
            result.setColumns(columns);
            result.setRows(rows);
            result.setRowCount(rowCount);
            result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            result.setExecutionTimeMs(System.currentTimeMillis() - startTime);
            result.setColumns(List.of());
            result.setRows(List.of());
            result.setRowCount(0);
        } finally {
            try { if (rs != null) rs.close(); } catch (Exception ignored) {}
            try { if (stmt != null) stmt.close(); } catch (Exception ignored) {}
            try { if (connection != null) connection.close(); } catch (Exception ignored) {}
        }

        return result;
    }

    private String detectSqlType(String sql) {
        String upper = sql.toUpperCase().trim();
        if (upper.startsWith("SELECT")) return "SELECT";
        if (upper.startsWith("SHOW")) return "SHOW";
        if (upper.startsWith("DESCRIBE") || upper.startsWith("DESC")) return "DESCRIBE";
        if (upper.startsWith("EXPLAIN")) return "EXPLAIN";
        if (upper.startsWith("CREATE")) return "CREATE";
        if (upper.startsWith("ALTER")) return "ALTER";
        if (upper.startsWith("DROP")) return "DROP";
        if (upper.startsWith("INSERT")) return "INSERT";
        if (upper.startsWith("UPDATE")) return "UPDATE";
        if (upper.startsWith("DELETE")) return "DELETE";
        return "UNKNOWN";
    }
}
