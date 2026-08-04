package com.bigdata.modules.devops.filesystem;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.devops.entity.StorageConfig;
import com.bigdata.modules.devops.mapper.StorageConfigMapper;
import org.apache.hadoop.conf.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FileSystemFactory {

    private static final Logger logger = LoggerFactory.getLogger(FileSystemFactory.class);

    private static final Map<String, String> SCHEME_TO_STORAGE_TYPE = Map.of(
            "hdfs", "hdfs",
            "jfs", "juicefs",
            "s3a", "s3",
            "s3", "s3",
            "file", "local"
    );

    @Autowired
    private LocalFileSystem localFileSystem;

    @Autowired
    private HdfsFileSystem hdfsFileSystem;

    @Autowired
    private S3FileSystem s3FileSystem;

    @Autowired
    private StorageConfigMapper storageConfigMapper;

    private final Map<String, FileSystem> fileSystemCache = new ConcurrentHashMap<>();

    private final Map<String, org.apache.hadoop.fs.FileSystem> hadoopFsCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        logger.info("File system factory initialized");
        initializeDefaultLocalFileSystem();
        initializeFromFileSystemConfigs();
    }

    @PreDestroy
    public void destroy() {
        logger.info("Closing all file system connections...");
        fileSystemCache.forEach((key, fs) -> {
            try {
                fs.close();
            } catch (Exception e) {
                logger.error("Failed to close file system: {}", key, e);
            }
        });
        fileSystemCache.clear();

        hadoopFsCache.forEach((key, fs) -> {
            try {
                fs.close();
            } catch (Exception e) {
                logger.error("Failed to close hadoop file system: {}", key, e);
            }
        });
        hadoopFsCache.clear();
    }

    public FileSystem getFileSystem(String storageType) {
        return getFileSystem(storageType, null);
    }

    public FileSystem getFileSystem(String storageType, Long configId) {
        String cacheKey = buildCacheKey(storageType, configId);
        FileSystem cachedFs = fileSystemCache.get(cacheKey);
        if (cachedFs != null && cachedFs.isConnected()) {
            logger.info("Returning cached file system: {}", cacheKey);
            return cachedFs;
        }

        try {
            FileSystem fs = createFileSystem(storageType, configId);
            fileSystemCache.put(cacheKey, fs);

            // 如果是HdfsFileSystem，将内部的Hadoop FileSystem也缓存到hadoopFsCache
            if (fs instanceof HdfsFileSystem) {
                HdfsFileSystem hdfsFs = (HdfsFileSystem) fs;
                org.apache.hadoop.fs.FileSystem hadoopFs = hdfsFs.getHadoopFs();
                String endpoint = hdfsFs.getEndpoint();
                if (hadoopFs != null && endpoint != null) {
                    try {
                        URI endpointUri = new URI(endpoint);
                        String scheme = endpointUri.getScheme();
                        String volume = endpointUri.getAuthority();
                        String hadoopCacheKey = buildHadoopFsCacheKey(
                                scheme != null ? scheme : storageType,
                                volume
                        );
                        hadoopFsCache.put(hadoopCacheKey, hadoopFs);
                        logger.info("Also cached hadoop file system in hadoopFsCache: {}", hadoopCacheKey);
                    } catch (Exception e) {
                        logger.warn("Failed to cache hadoop file system from HdfsFileSystem: {}", e.getMessage());
                    }
                }
            }

            logger.info("Created and cached new file system instance: {}", cacheKey);
            return fs;
        } catch (Exception e) {
            logger.error("Failed to create file system for type: {}, configId: {}", storageType, configId, e);
            throw new BusinessException("Failed to initialize file system: " + e.getMessage());
        }
    }

    private FileSystem createFileSystem(String storageType, Long configId) throws Exception {
        Map<String, String> config = buildConfig(storageType, configId);
        switch (storageType.toLowerCase()) {
            case "local":
                localFileSystem.initialize(config);
                return localFileSystem;

            case "hdfs":
                hdfsFileSystem.initialize(config);
                return hdfsFileSystem;

            case "s3":
                s3FileSystem.initialize(config);
                return s3FileSystem;

            default:
                throw new BusinessException("Unsupported storage type: " + storageType);
        }
    }

    private Map<String, String> buildConfig(String storageType, Long configId) {
        Map<String, String> config = new java.util.HashMap<>();
        if (configId != null) {
            StorageConfig storageConfig = storageConfigMapper.selectById(configId);
            if (storageConfig != null) {
                if (storageConfig.getEndpoint() != null) {
                    config.put("endpoint", storageConfig.getEndpoint());
                    config.put("fs.defaultFS", storageConfig.getEndpoint());
                }
                if (storageConfig.getBucketName() != null) {
                    config.put("bucketName", storageConfig.getBucketName());
                }
                if (storageConfig.getAccessKey() != null) {
                    config.put("accessKey", storageConfig.getAccessKey());
                }
                if (storageConfig.getSecretKey() != null) {
                    config.put("secretKey", storageConfig.getSecretKey());
                }
                if (storageConfig.getRootPath() != null) {
                    config.put("defaultPath", storageConfig.getRootPath());
                    config.put("prefix", storageConfig.getRootPath());
                    config.put("basePath", storageConfig.getRootPath());
                }
                if (storageConfig.getLocalMountPath() != null) {
                    config.put("localMountPath", storageConfig.getLocalMountPath());
                }
                if (storageConfig.getExtraParams() != null) {
                    parseExtraParams(config, storageConfig.getExtraParams());
                }
            }
        }
        setDefaultsForStorageType(storageType, config);

        return config;
    }

    private void parseExtraParams(Map<String, String> config, String extraParams) {
        try {
            if (extraParams.startsWith("{") || extraParams.startsWith("[")) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, String> extraMap = mapper.readValue(extraParams, Map.class);
                config.putAll(extraMap);
            } else {
                String[] pairs = extraParams.split(",");
                for (String pair : pairs) {
                    String[] keyValue = pair.split("=", 2);
                    if (keyValue.length == 2) {
                        config.put(keyValue[0].trim(), keyValue[1].trim());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse extra params: {}", extraParams, e);
        }
    }

    private void setDefaultsForStorageType(String storageType, Map<String, String> config) {
        switch (storageType.toLowerCase()) {
            case "local":
                config.putIfAbsent("basePath", System.getProperty("user.home") + "/filebrowser");
                break;
            case "hdfs":
                config.putIfAbsent("endpoint", "hdfs://localhost:9000");
                config.putIfAbsent("user", "mxq");
                config.putIfAbsent("defaultPath", "/user/hadoop");
                break;
            case "s3":
                config.putIfAbsent("endpoint", "https://s3.amazonaws.com");
                config.putIfAbsent("region", "us-east-1");
                config.putIfAbsent("bucketName", "default-bucket");
                break;
        }
    }

    private void initializeDefaultLocalFileSystem() {
        try {
            getFileSystem("local");
            logger.info("Default local file system initialized successfully");
        } catch (Exception e) {
            logger.warn("Failed to initialize default local file system: {}", e.getMessage());
        }
    }

    /**
     * 从数据库中读取所有启用的存储配置，初始化文件系统实例并缓存
     */
    private void initializeFromFileSystemConfigs() {
        try {
            QueryWrapper<StorageConfig> query = new QueryWrapper<>();
            query.eq("status", 1);
            List<StorageConfig> configs = storageConfigMapper.selectList(query);

            if (configs == null || configs.isEmpty()) {
                logger.info("No storage configs found in database, skipping auto-initialization");
                return;
            }

            logger.info("Found {} storage config(s) in database, initializing...", configs.size());

            for (StorageConfig config : configs) {
                try {
                    String storageType = config.getStorageType();
                    Long configId = config.getId();

                    // 初始化自定义FileSystem（用于文件浏览操作）
                    //getFileSystem(storageType, configId);

                    // 同时初始化Hadoop FileSystem（用于数据读取操作）
                    //initHadoopFileSystemFromConfig(config);

                    logger.info("Initialized file system: name={}, type={}, id={}",
                            config.getStorageName(), storageType, configId);
                } catch (Exception e) {
                    logger.error("Failed to initialize file system: name={}, type={}, id={}, error={}",
                            config.getStorageName(), config.getStorageType(), config.getId(), e.getMessage());
                }
            }

            logger.info("File system auto-initialization completed. fileSystemCache={}, hadoopFsCache={}",
                    fileSystemCache.size(), hadoopFsCache.size());
        } catch (Exception e) {
            logger.error("Failed to load storage configs from database: {}", e.getMessage());
        }
    }

    /**
     * 根据StorageConfig初始化Hadoop FileSystem并缓存到hadoopFsCache
     */
    private void initHadoopFileSystemFromConfig(StorageConfig storageConfig) {
        String endpoint = storageConfig.getEndpoint();
        if (endpoint == null || endpoint.isEmpty()) {
            logger.warn("StorageConfig id={} has no endpoint, skipping hadoop fs initialization", storageConfig.getId());
            return;
        }

        try {
            URI endpointUri = new URI(endpoint);
            String scheme = endpointUri.getScheme();
            String volume = endpointUri.getAuthority();

            if (scheme == null || scheme.isEmpty()) {
                logger.warn("StorageConfig id={} endpoint has no scheme: {}", storageConfig.getId(), endpoint);
                return;
            }

            String cacheKey = buildHadoopFsCacheKey(scheme, volume);

            // 已缓存则跳过
            if (hadoopFsCache.containsKey(cacheKey)) {
                logger.info("Hadoop file system already cached: {}", cacheKey);
                return;
            }

            Configuration conf = new Configuration();
            applySchemeDefaults(conf, scheme.toLowerCase());
            applyStorageConfig(conf, storageConfig);

            String uriStr = volume != null
                    ? scheme.toLowerCase() + "://" + volume
                    : scheme.toLowerCase() + ":///";
            org.apache.hadoop.fs.FileSystem fs = org.apache.hadoop.fs.FileSystem.get(new URI(uriStr), conf);
            hadoopFsCache.put(cacheKey, fs);
            logger.info("Initialized and cached hadoop file system from db config: {}", cacheKey);
        } catch (Exception e) {
            logger.error("Failed to init hadoop file system from config id={}: {}", storageConfig.getId(), e.getMessage());
        }
    }

    public void removeFileSystem(String storageType, Long configId) {
        String cacheKey = buildCacheKey(storageType, configId);
        FileSystem fs = fileSystemCache.remove(cacheKey);
        if (fs != null) {
            try {
                fs.close();
                logger.info("Removed and closed file system: {}", cacheKey);
            } catch (Exception e) {
                logger.error("Failed to close file system: {}", cacheKey, e);
            }
        }
    }

    public void clearCache() {
        destroy();
    }

    private String buildCacheKey(String storageType, Long configId) {
        if (configId != null) {
            return storageType.toLowerCase() + ":" + configId;
        }
        return storageType.toLowerCase() + ":default";
    }

    /**
     * 通过协议+卷名获取Hadoop FileSystem实例
     * 解析URI中的scheme和authority，查找StorageConfig中匹配的配置来创建FileSystem
     * 例如: jfs://myjfs/test/product -> scheme=jfs, volume=myjfs
     *       hdfs://namenode:9000/path -> scheme=hdfs, volume=namenode:9000
     *
     * @param directoryPath 带协议的完整路径，如 jfs://myjfs/test/product
     * @return Hadoop FileSystem实例
     */
    public org.apache.hadoop.fs.FileSystem getHadoopFileSystem(String directoryPath) {
        try {
            URI uri = new URI(directoryPath);
            String scheme = uri.getScheme();
            if (scheme == null || scheme.isEmpty()) {
                throw new BusinessException("路径必须包含文件系统协议，例如 hdfs://、file://、jfs://");
            }

            String volume = uri.getAuthority();
            return getHadoopFileSystem(scheme, volume);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to create hadoop file system for path: {}", directoryPath, e);
            throw new BusinessException("获取文件系统失败: " + e.getMessage());
        }
    }

    /**
     * 通过协议+卷名获取Hadoop FileSystem实例
     * 优先从缓存获取，缓存未命中则查找StorageConfig创建并缓存
     *
     * @param scheme  协议，如 jfs、hdfs、file、s3a
     * @param volume  卷名/authority，如 myjfs、namenode:9000，可为null
     * @return Hadoop FileSystem实例
     */
    public org.apache.hadoop.fs.FileSystem getHadoopFileSystem(String scheme, String volume) {
        String cacheKey = buildHadoopFsCacheKey(scheme, volume);

        // 从缓存获取
        org.apache.hadoop.fs.FileSystem cachedFs = hadoopFsCache.get(cacheKey);
        if (cachedFs != null) {
            try {
                cachedFs.getStatus(new org.apache.hadoop.fs.Path("/"));
                logger.info("Returning cached hadoop file system: {}", cacheKey);
                return cachedFs;
            } catch (Exception e) {
                logger.warn("Cached hadoop file system is no longer valid: {}, recreating...", cacheKey);
                hadoopFsCache.remove(cacheKey);
                try { cachedFs.close(); } catch (Exception ignored) {}
            }
        }

        // 缓存未命中，创建新的FileSystem实例
        try {
            // 根据协议确定存储类型
            String storageType = SCHEME_TO_STORAGE_TYPE.getOrDefault(scheme.toLowerCase(), scheme.toLowerCase());

            // 查找StorageConfig中匹配的配置
            StorageConfig storageConfig = findStorageConfig(storageType, volume);
            Configuration conf = new Configuration();

            // 根据协议设置FileSystem实现类
            applySchemeDefaults(conf, scheme.toLowerCase());

            if (storageConfig != null) {
                applyStorageConfig(conf, storageConfig);
            } else if ("file".equals(scheme.toLowerCase())) {
                // 本地文件系统无需额外配置
            } else {
                logger.warn("No StorageConfig found for scheme={}, volume={}, using default Hadoop config", scheme, volume);
            }

            String uriStr = volume != null
                    ? scheme.toLowerCase() + "://" + volume
                    : scheme.toLowerCase() + ":///";
            org.apache.hadoop.fs.FileSystem fs = org.apache.hadoop.fs.FileSystem.get(new URI(uriStr), conf);
            hadoopFsCache.put(cacheKey, fs);
            logger.info("Created and cached hadoop file system: {}", cacheKey);
            return fs;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to create hadoop file system for scheme={}, volume={}", scheme, volume, e);
            throw new BusinessException("获取文件系统失败: " + e.getMessage());
        }
    }

    /**
     * 构建Hadoop FileSystem缓存key: 协议://卷名
     * 例如: jfs://myjfs, hdfs://namenode:9000, file://default
     */
    private String buildHadoopFsCacheKey(String scheme, String volume) {
        return scheme.toLowerCase() + "://" + (volume != null ? volume : "default");
    }

    /**
     * 查找StorageConfig中匹配协议和卷名的配置
     */
    private StorageConfig findStorageConfig(String storageType, String authority) {
        if (authority == null || authority.isEmpty()) {
            return null;
        }

        QueryWrapper<StorageConfig> query = new QueryWrapper<>();
        query.eq("storage_type", storageType);
        query.eq("status", 1);

        // 对于juicefs，authority是卷名，匹配endpoint中包含该卷名的配置
        // 对于hdfs，authority是namenode地址，匹配endpoint
        // 对于s3，authority是bucket或endpoint
        query.and(w -> w
                .like("endpoint", authority)
                .or()
                .like("endpoint", storageType + "://" + authority)
        );

        query.last("LIMIT 1");
        return storageConfigMapper.selectOne(query);
    }

    /**
     * 将StorageConfig中的配置应用到Hadoop Configuration
     */
    private void applyStorageConfig(Configuration conf, StorageConfig storageConfig) {
        if (storageConfig.getEndpoint() != null) {
            conf.set("fs.defaultFS", storageConfig.getEndpoint());
        }
        if (storageConfig.getAccessKey() != null) {
            conf.set("fs.s3a.access.key", storageConfig.getAccessKey());
            conf.set("fs.jfs.access.key", storageConfig.getAccessKey());
        }
        if (storageConfig.getSecretKey() != null) {
            conf.set("fs.s3a.secret.key", storageConfig.getSecretKey());
            conf.set("fs.jfs.secret.key", storageConfig.getSecretKey());
        }
        if (storageConfig.getExtraParams() != null) {
            parseExtraParamsToConf(conf, storageConfig.getExtraParams());
        }
    }

    /**
     * 根据协议设置Hadoop FileSystem实现类的默认配置
     */
    private void applySchemeDefaults(Configuration conf, String scheme) {
        switch (scheme) {
            case "jfs":
                conf.set("fs.jfs.impl", "io.juicefs.JuiceFileSystem");
                break;
            case "s3a":
                conf.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem");
                break;
            default:
                break;
        }
    }

    private void parseExtraParamsToConf(Configuration conf, String extraParams) {
        try {
            if (extraParams.startsWith("{") || extraParams.startsWith("[")) {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, String> extraMap = mapper.readValue(extraParams, Map.class);
                extraMap.forEach(conf::set);
            } else {
                String[] pairs = extraParams.split(",");
                for (String pair : pairs) {
                    String[] keyValue = pair.split("=", 2);
                    if (keyValue.length == 2) {
                        conf.set(keyValue[0].trim(), keyValue[1].trim());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse extra params to hadoop conf: {}", extraParams, e);
        }
    }

    /**
     * 移除缓存的Hadoop FileSystem
     */
    public void removeHadoopFileSystem(String scheme, String volume) {
        String cacheKey = buildHadoopFsCacheKey(scheme, volume);
        org.apache.hadoop.fs.FileSystem fs = hadoopFsCache.remove(cacheKey);
        if (fs != null) {
            try {
                fs.close();
                logger.info("Removed and closed hadoop file system: {}", cacheKey);
            } catch (Exception e) {
                logger.error("Failed to close hadoop file system: {}", cacheKey, e);
            }
        }
    }
}
