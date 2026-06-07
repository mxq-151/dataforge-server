package com.bigdata.modules.devops.vo;

import java.io.Serializable;
import java.util.List;

public class FileDataVO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String fileName;
    private String filePath;
    private String fileFormat;
    private String compression;
    private Long fileSize;
    private List<String> columns;
    private List<Object> records;

    public FileDataVO() {
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getFileFormat() {
        return fileFormat;
    }

    public void setFileFormat(String fileFormat) {
        this.fileFormat = fileFormat;
    }

    public String getCompression() {
        return compression;
    }

    public void setCompression(String compression) {
        this.compression = compression;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public List<String> getColumns() {
        return columns;
    }

    public void setColumns(List<String> columns) {
        this.columns = columns;
    }

    public List<Object> getRecords() {
        return records;
    }

    public void setRecords(List<Object> records) {
        this.records = records;
    }
}
