package com.bigdata.modules.devops.filesystem;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.devops.entity.StorageConfig;
import com.bigdata.modules.devops.mapper.StorageConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import netscape.javascript.JSObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FileSystemFactory {

    private static final Logger logger = LoggerFactory.getLogger(FileSystemFactory.class);

    @Autowired
    private LocalFileSystem localFileSystem;

    @Autowired
    private HdfsFileSystem hdfsFileSystem;

    @Autowired
    private S3FileSystem s3FileSystem;

    @Autowired
    private StorageConfigMapper storageConfigMapper;

    private final Map<String, FileSystem> fileSystemCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        logger.info("File system factory initialized");
        initializeDefaultLocalFileSystem();
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
        System.out.println(config);
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
}
