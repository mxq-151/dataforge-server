package com.bigdata.modules.devops.filesystem;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

public interface FileSystem {

    void initialize(Map<String, String> config) throws Exception;

    boolean isConnected();

    List<Map<String, Object>> listFiles(String path) throws Exception;

    void createDirectory(String path) throws Exception;

    void deleteFile(String path) throws Exception;

    void renameFile(String oldPath, String newPath) throws Exception;

    InputStream readFile(String path) throws Exception;

    void uploadFile(String path, InputStream inputStream, long size) throws Exception;

    boolean exists(String path) throws Exception;

    void close() throws Exception;
}
