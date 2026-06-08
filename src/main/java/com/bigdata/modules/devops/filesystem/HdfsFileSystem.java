package com.bigdata.modules.devops.filesystem;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.util.*;

@Component
public class HdfsFileSystem implements FileSystem {

    private static final Logger logger = LoggerFactory.getLogger(HdfsFileSystem.class);

    private org.apache.hadoop.fs.FileSystem fs;
    private String defaultPath;
    private String endpoint;

    @Override
    public void initialize(Map<String, String> config) throws Exception {
        endpoint = config.getOrDefault("endpoint", "jfs://myjfs/");
        String user = config.getOrDefault("user", "mxq");

        Configuration hadoopConfig = new Configuration();
        Iterator<Map.Entry<String,String>> iter= config.entrySet().iterator();
        System.out.println(config);
        while (iter.hasNext()) {
            Map.Entry<String,String> entry=iter.next();
            System.out.println(entry.getKey()+":"+entry.getValue());
            hadoopConfig.set(entry.getKey(),entry.getValue());
        }
        
        
        try{
            this.fs = org.apache.hadoop.fs.FileSystem.get(new URI(endpoint), hadoopConfig, user);
        this.defaultPath = config.getOrDefault("defaultPath", "/user/" + user);

        ensureDirectoryExists(defaultPath);
        logger.info("HDFS file system initialized with endpoint: {}, default path: {}", endpoint, defaultPath);
        }catch(Exception ex)
        {
            ex.printStackTrace();
        }
        
    }

    /**
     * 获取内部的Hadoop FileSystem实例
     */
    public org.apache.hadoop.fs.FileSystem getHadoopFs() {
        return fs;
    }

    /**
     * 获取endpoint（协议+卷名）
     */
    public String getEndpoint() {
        return endpoint;
    }

    @Override
    public boolean isConnected() {
        return fs != null;
    }

    @Override
    public List<Map<String, Object>> listFiles(String path) throws Exception {
        String fullPath = normalizePath(path);
        Path hdfsPath = new Path(fullPath);

        if (!fs.exists(hdfsPath)) {
            throw new FileNotFoundException("HDFS path not found: " + path);
        }

        FileStatus[] fileStatuses = fs.listStatus(hdfsPath);
        List<Map<String, Object>> fileList = new ArrayList<>();

        for (FileStatus status : fileStatuses) {
            Map<String, Object> fileInfo = new LinkedHashMap<>();
            fileInfo.put("name", status.getPath().getName());
            fileInfo.put("isDir", status.isDirectory());
            fileInfo.put("size", status.isDirectory() ? 0 : status.getLen());
            fileInfo.put("modifiedTime", formatTime(status.getModificationTime()));
            fileInfo.put("permissions", status.getPermission().toString());
            fileInfo.put("path", getRelativePath(status.getPath()));
            fileInfo.put("replication", status.getReplication());
            fileInfo.put("owner", status.getOwner());
            fileInfo.put("group", status.getGroup());

            fileList.add(fileInfo);
        }

        fileList.sort((a, b) -> {
            boolean aDir = (Boolean) a.get("isDir");
            boolean bDir = (Boolean) b.get("isDir");
            if (aDir != bDir) return aDir ? -1 : 1;
            return ((String) a.get("name")).compareToIgnoreCase((String) b.get("name"));
        });

        return fileList;
    }

    @Override
    public void createDirectory(String path) throws Exception {
        String fullPath = normalizePath(path);
        Path dirPath = new Path(fullPath);

        if (fs.exists(dirPath)) {
            throw new IOException("HDFS directory already exists: " + path);
        }

        fs.mkdirs(dirPath);
        logger.info("Created HDFS directory: {}", path);
    }

    @Override
    public void deleteFile(String path) throws Exception {
        String fullPath = normalizePath(path);
        Path targetPath = new Path(fullPath);

        if (!fs.exists(targetPath)) {
            throw new FileNotFoundException("HDFS path not found: " + path);
        }

        boolean deleted = fs.delete(targetPath, true);
        if (!deleted) {
            throw new IOException("Failed to delete HDFS path: " + path);
        }
        logger.info("Deleted HDFS path: {}", path);
    }

    @Override
    public void renameFile(String oldPath, String newPath) throws Exception {
        String fullOldPath = normalizePath(oldPath);
        String fullNewPath = normalizePath(newPath);

        Path oldFilePath = new Path(fullOldPath);
        Path newFilePath = new Path(fullNewPath);

        if (!fs.exists(oldFilePath)) {
            throw new FileNotFoundException("HDFS source not found: " + oldPath);
        }

        boolean renamed = fs.rename(oldFilePath, newFilePath);
        if (!renamed) {
            throw new IOException("Failed to rename HDFS path: " + oldPath);
        }
        logger.info("Renamed HDFS path: {} -> {}", oldPath, newPath);
    }

    @Override
    public InputStream readFile(String path) throws Exception {
        String fullPath = normalizePath(path);
        Path filePath = new Path(fullPath);

        if (!fs.exists(filePath)) {
            throw new FileNotFoundException("HDFS file not found: " + path);
        }

        if (fs.getFileStatus(filePath).isDirectory()) {
            throw new IOException("Path is a directory: " + path);
        }

        long maxSize = 10 * 1024 * 1024; // 10MB
        long fileSize = fs.getFileStatus(filePath).getLen();
        if (fileSize > maxSize) {
            throw new IOException("File too large for preview (max 10MB): " + fileSize + " bytes");
        }

        FSDataInputStream inputStream = fs.open(filePath);
        return new BufferedInputStream(inputStream);
    }

    @Override
    public void uploadFile(String path, InputStream inputStream, long size) throws Exception {
        String fullPath = normalizePath(path);
        Path targetPath = new Path(fullPath);

        try (FSDataOutputStream out = fs.create(targetPath, true)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
            }
            logger.info("Uploaded file to HDFS: {} ({} bytes)", path, totalRead);
        }
    }

    @Override
    public boolean exists(String path) throws Exception {
        String fullPath = normalizePath(path);
        return fs.exists(new Path(fullPath));
    }

    @Override
    public void close() throws Exception {
        if (fs != null) {
            fs.close();
            fs = null;
            logger.info("HDFS file system closed");
        }
    }

    private String normalizePath(String path) {
        return path;
    }

    private void ensureDirectoryExists(String path) throws IOException {
        Path dirPath = new Path(path);
        if (!fs.exists(dirPath)) {
            fs.mkdirs(dirPath);
        }
    }

    private String getRelativePath(Path absolutePath) {
        String absoluteStr = absolutePath.toString();

        logger.debug("原始绝对路径: {}", absoluteStr);

        String relativePath = removeSchemePrefix(absoluteStr);

        if (relativePath.startsWith(defaultPath)) {
            relativePath = relativePath.substring(defaultPath.length());
        }

        if (!relativePath.startsWith("/")) {
            relativePath = "/" + relativePath;
        }

        logger.debug("转换后相对路径: {} (defaultPath: {})", relativePath, defaultPath);
        return relativePath;
    }

    private String removeSchemePrefix(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }

        int schemeEnd = path.indexOf("://");
        if (schemeEnd != -1) {
            int authorityStart = schemeEnd + 3;
            int pathStart = path.indexOf('/', authorityStart);

            if (pathStart != -1) {
                String schemePart = path.substring(0, pathStart);
                logger.debug("检测到文件系统协议前缀: {}, 已移除", schemePart);
                return path.substring(pathStart);
            } else {
                logger.debug("检测到文件系统协议前缀但无路径部分: {}", path);
                return "/";
            }
        }

        return path;
    }

    private String formatTime(long millis) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(millis));
    }
}
