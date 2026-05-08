package com.bigdata.modules.devops.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.devops.filesystem.FileSystem;
import com.bigdata.modules.devops.filesystem.FileSystemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Service
public class FileExplorerService {

    private static final Logger logger = LoggerFactory.getLogger(FileExplorerService.class);

    @Autowired
    private FileSystemFactory fileSystemFactory;

    public List<Map<String, Object>> listFiles(String storageType, String path, Long configId) {
        try {
            FileSystem fs = fileSystemFactory.getFileSystem(storageType, configId);
            return fs.listFiles(path);
        } catch (Exception e) {
            logger.error("Failed to list files for storageType: {}, path: {}", storageType, path, e);
            throw new BusinessException("获取文件列表失败: " + e.getMessage());
        }
    }

    public void createDirectory(String storageType, String parentPath, String dirName, Long configId) {
        if (dirName == null || dirName.isEmpty() || dirName.matches("[\\\\/:*?\"<>|]")) {
            throw new BusinessException("目录名称包含非法字符");
        }

        try {
            String fullPath = buildFullPath(parentPath, dirName);
            FileSystem fs = fileSystemFactory.getFileSystem(storageType, configId);

            if (fs.exists(fullPath)) {
                throw new BusinessException("目录已存在: " + fullPath);
            }

            fs.createDirectory(fullPath);
            logger.info("Created directory: {} in {} filesystem", fullPath, storageType);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to create directory: {}/{}", parentPath, dirName, e);
            throw new BusinessException("创建目录失败: " + e.getMessage());
        }
    }

    public void deleteFile(String storageType, String filePath, Long configId) {
        try {
            FileSystem fs = fileSystemFactory.getFileSystem(storageType, configId);

            if (!fs.exists(filePath)) {
                throw new BusinessException("文件或目录不存在: " + filePath);
            }

            fs.deleteFile(filePath);
            logger.info("Deleted: {} from {} filesystem", filePath, storageType);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to delete: {} from {}", filePath, storageType, e);
            throw new BusinessException("删除失败: " + e.getMessage());
        }
    }

    public void renameFile(String storageType, String oldPath, String newName, Long configId) {
        if (newName == null || newName.isEmpty() || newName.matches("[\\\\/:*?\"<>|]")) {
            throw new BusinessException("新名称包含非法字符");
        }

        try {
            String newPath = buildFullPath(getParentPath(oldPath), newName);
            FileSystem fs = fileSystemFactory.getFileSystem(storageType, configId);

            fs.renameFile(oldPath, newPath);
            logger.info("Renamed: {} -> {} in {} filesystem", oldPath, newPath, storageType);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to rename: {} to {} in {}", oldPath, newName, storageType, e);
            throw new BusinessException("重命名失败: " + e.getMessage());
        }
    }

    public void uploadFile(String storageType, String filePath, InputStream inputStream, Long configId, long fileSize) {
        try {
            FileSystem fs = fileSystemFactory.getFileSystem(storageType, configId);

            String parentPath = getParentPath(filePath);
            if (!fs.exists(parentPath)) {
                fs.createDirectory(parentPath);
                logger.info("Created parent directory: {}", parentPath);
            }

            if (fs.exists(filePath)) {
                throw new BusinessException("文件已存在: " + filePath);
            }

            fs.uploadFile(filePath, inputStream, fileSize);
            logger.info("Uploaded file: {} ({} bytes) to {} filesystem", filePath, fileSize, storageType);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to upload file: {} to {}", filePath, storageType, e);
            throw new BusinessException("上传文件失败: " + e.getMessage());
        }
    }

    public InputStream readFile(String storageType, String filePath, Long configId) throws Exception {
        try {
            FileSystem fs = fileSystemFactory.getFileSystem(storageType, configId);

            if (!fs.exists(filePath)) {
                throw new BusinessException("文件不存在: " + filePath);
            }

            InputStream inputStream = fs.readFile(filePath);
            logger.info("Reading file: {} from {} filesystem", filePath, storageType);
            return inputStream;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to read file: {} from {}", filePath, storageType, e);
            throw new BusinessException("读取文件失败: " + e.getMessage());
        }
    }

    public boolean testConnection(String storageType, Long configId) {
        try {
            FileSystem fs = fileSystemFactory.getFileSystem(storageType, configId);
            return fs.isConnected();
        } catch (Exception e) {
            logger.error("Connection test failed for {}: {}", storageType, e.getMessage());
            return false;
        }
    }

    public List<String> getSupportedStorageTypes() {
        return List.of("local", "hdfs", "s3");
    }

    private String buildFullPath(String parentPath, String name) {
        if (parentPath == null || parentPath.isEmpty() || "/".equals(parentPath)) {
            return "/" + name;
        }

        String normalizedParent = parentPath.replaceAll("/+$", "");
        return normalizedParent + "/" + name;
    }

    private String getParentPath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "/";
        }

        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) {
            return "/";
        }

        return path.substring(0, lastSlash);
    }
}
