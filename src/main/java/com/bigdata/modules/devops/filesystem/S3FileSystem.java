package com.bigdata.modules.devops.filesystem;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;

@Component
public class S3FileSystem implements FileSystem {

    private static final Logger logger = LoggerFactory.getLogger(S3FileSystem.class);

    private S3Client s3Client;
    private String bucketName;
    private String prefix;

    @Override
    public void initialize(Map<String, String> config) throws Exception {
        this.bucketName = config.get("bucketName");
        if (bucketName == null || bucketName.isEmpty()) {
            throw new IllegalArgumentException("S3 bucket name is required");
        }

        String endpoint = config.getOrDefault("endpoint", "https://s3.amazonaws.com");
        String accessKey = config.get("accessKey");
        String secretKey = config.get("secretKey");
        String region = config.getOrDefault("region", "us-east-1");
        this.prefix = config.getOrDefault("prefix", "").replaceAll("/+$", "");

        var s3Builder = S3Client.builder()
                .region(Region.of(region));

        if (accessKey != null && secretKey != null) {
            s3Builder.credentialsProvider(
                    StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)
                    )
            );
        }

        if (endpoint != null && !endpoint.contains("amazonaws.com")) {
            s3Builder.endpointOverride(URI.create(endpoint));
            s3Builder.serviceConfiguration(c -> c.pathStyleAccessEnabled(true));
        }

        this.s3Client = s3Builder.build();

        ensureBucketExists();
        logger.info("S3 file system initialized with bucket: {}, endpoint: {}", bucketName, endpoint);
    }

    @Override
    public boolean isConnected() {
        if (s3Client == null || bucketName == null) return false;

        try {
            s3Client.headBucket(b -> b.bucket(bucketName));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<Map<String, Object>> listFiles(String path) throws Exception {
        String fullPrefix = buildFullPrefix(path);

        ListObjectsV2Request request = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(fullPrefix)
                .delimiter("/")
                .maxKeys(1000)
                .build();

        ListObjectsV2Response response = s3Client.listObjectsV2(request);
        List<Map<String, Object>> fileList = new ArrayList<>();

        for (CommonPrefix commonPrefix : response.commonPrefixes()) {
            String dirName = commonPrefix.prefix().replaceFirst(".*/([^/]+)/$", "$1");

            Map<String, Object> fileInfo = new LinkedHashMap<>();
            fileInfo.put("name", dirName);
            fileInfo.put("isDir", true);
            fileInfo.put("size", 0);
            fileInfo.put("modifiedTime", "");
            fileInfo.put("permissions", "drwxr-xr-x");
            fileInfo.put("path", buildRelativePath(commonPrefix.prefix()));

            fileList.add(fileInfo);
        }

        for (S3Object object : response.contents()) {
            String key = object.key();
            if (key.equals(fullPrefix) || key.endsWith("/")) continue;

            String fileName = key.substring(key.lastIndexOf('/') + 1);

            Map<String, Object> fileInfo = new LinkedHashMap<>();
            fileInfo.put("name", fileName);
            fileInfo.put("isDir", false);
            fileInfo.put("size", object.size());
            fileInfo.put("modifiedTime", formatTime(object.lastModified()));
            fileInfo.put("permissions", "-rw-r--r--");
            fileInfo.put("path", buildRelativePath(key));

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
        String fullKey = buildFullPrefix(path);
        if (!fullKey.endsWith("/")) {
            fullKey += "/";
        }

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fullKey)
                .build();

        s3Client.putObject(request, RequestBody.empty());
        logger.info("Created S3 directory: {}", path);
    }

    @Override
    public void deleteFile(String path) throws Exception {
        String fullKey = buildFullPrefix(path);

        try {
            s3Client.headObject(b -> b.bucket(bucketName).key(fullKey));

            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fullKey)
                    .build();

            s3Client.deleteObject(request);
            logger.info("Deleted S3 file: {}", path);
        } catch (NoSuchKeyException e) {
            deleteDirectoryRecursive(path);
        }
    }

    @Override
    public void renameFile(String oldPath, String newPath) throws Exception {
        String oldKey = buildFullPrefix(oldPath);
        String newKey = buildFullPrefix(newPath);

        CopyObjectRequest copyRequest = CopyObjectRequest.builder()
                .sourceBucket(bucketName)
                .sourceKey(oldKey)
                .destinationBucket(bucketName)
                .destinationKey(newKey)
                .build();

        s3Client.copyObject(copyRequest);

        DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(oldKey)
                .build();

        s3Client.deleteObject(deleteRequest);
        logger.info("Renamed S3 path: {} -> {}", oldPath, newPath);
    }

    @Override
    public InputStream readFile(String path) throws Exception {
        String fullKey = buildFullPrefix(path);

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(fullKey)
                .build();

        ResponseInputStream<GetObjectResponse> responseStream = s3Client.getObject(request);
        
        try {
            long contentLength = responseStream.response().contentLength();
            long maxSize = 10 * 1024 * 1024; // 10MB
            
            if (contentLength > maxSize) {
                responseStream.close();
                throw new IOException("File too large for preview (max 10MB)");
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            
            while ((bytesRead = responseStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            
            responseStream.close();
            return new ByteArrayInputStream(outputStream.toByteArray());
        } catch (IOException e) {
            responseStream.close();
            throw e;
        }
    }

    @Override
    public void uploadFile(String path, InputStream inputStream, long size) throws Exception {
        String fullKey = buildFullPrefix(path);

        byte[] bytes = inputStream.readAllBytes();

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(fullKey)
                .contentLength((long) bytes.length)
                .build();

        s3Client.putObject(request, RequestBody.fromBytes(bytes));
        logger.info("Uploaded file to S3: {} ({} bytes)", path, bytes.length);
    }

    @Override
    public boolean exists(String path) throws Exception {
        String fullKey = buildFullPrefix(path);

        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fullKey)
                    .build();

            s3Client.headObject(request);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public void close() throws Exception {
        if (s3Client != null) {
            s3Client.close();
            s3Client = null;
            logger.info("S3 file system closed");
        }
    }

    private String buildFullPrefix(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return prefix.isEmpty() ? "" : prefix + "/";
        }

        String normalizedPath = path.replaceAll("/+", "/");
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        normalizedPath = normalizedPath.substring(1);

        return prefix.isEmpty() ? normalizedPath : prefix + "/" + normalizedPath;
    }

    private String buildRelativePath(String s3Key) {
        if (prefix.isEmpty()) {
            return "/" + s3Key;
        }

        if (s3Key.startsWith(prefix)) {
            return "/" + s3Key.substring(prefix.length());
        }

        return "/" + s3Key;
    }

    private void ensureBucketExists() {
        try {
            HeadBucketRequest request = HeadBucketRequest.builder()
                    .bucket(bucketName)
                    .build();

            s3Client.headBucket(request);
        } catch (NoSuchBucketException e) {
            CreateBucketRequest createRequest = CreateBucketRequest.builder()
                    .bucket(bucketName)
                    .build();

            s3Client.createBucket(createRequest);
            logger.info("Created new S3 bucket: {}", bucketName);
        }
    }

    private void deleteDirectoryRecursive(String path) throws Exception {
        String fullPrefix = buildFullPrefix(path);
        if (!fullPrefix.endsWith("/")) {
            fullPrefix += "/";
        }

        ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(bucketName)
                .prefix(fullPrefix)
                .maxKeys(1000)
                .build();

        ListObjectsV2Response response = s3Client.listObjectsV2(listRequest);

        for (S3Object object : response.contents()) {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(bucketName)
                    .key(object.key())
                    .build();

            s3Client.deleteObject(deleteRequest);
        }

        logger.info("Deleted S3 directory recursively: {}", path);
    }

    private String formatTime(Instant instant) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(Date.from(instant));
    }
}
