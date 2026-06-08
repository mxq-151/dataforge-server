package com.bigdata.modules.devops.service;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.devops.filesystem.FileSystemFactory;
import com.bigdata.modules.devops.vo.FileDataVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.orc.OrcFile;
import org.apache.orc.Reader;
import org.apache.orc.RecordReader;
import org.apache.orc.TypeDescription;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetFileReader;
import org.apache.parquet.hadoop.util.HadoopInputFile;
import org.apache.parquet.schema.MessageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

@Service
public class FileDataReaderService {

    private static final Logger logger = LoggerFactory.getLogger(FileDataReaderService.class);
    private static final int MAX_RECORDS = 5;

    private static final Set<String> TEXT_FORMATS = Set.of("txt", "log", "text");
    private static final Set<String> CSV_FORMATS = Set.of("csv", "tsv");
    private static final Set<String> JSON_FORMATS = Set.of("json");
    private static final Set<String> PARQUET_FORMATS = Set.of("parquet", "parq");
    private static final Set<String> ORC_FORMATS = Set.of("orc");
    private static final Set<String> COMPRESSION_EXTENSIONS = Set.of("gz", "gzip", "bz2", "bzip2", "snappy", "lz4", "zst", "zstd");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private FileSystemFactory fileSystemFactory;

    public FileDataVO readFileData(String directoryPath) {
        try {
            URI uri = new URI(directoryPath);
            String scheme = uri.getScheme();
            if (scheme == null || scheme.isEmpty()) {
                throw new BusinessException("路径必须包含文件系统协议，例如 hdfs://、file://、jfs://");
            }

            // 通过FileSystemFactory获取Hadoop FileSystem实例
            org.apache.hadoop.fs.FileSystem fs = fileSystemFactory.getHadoopFileSystem(directoryPath);
            if (fs==null){
                throw new IOException("找不到文件系统实例");
            }
            Path dirPath = new Path(uri);
            if (!fs.exists(dirPath) || !fs.getFileStatus(dirPath).isDirectory()) {
                throw new BusinessException("目录不存在或不是目录: " + directoryPath);
            }

            FileStatus[] fileStatuses = fs.listStatus(dirPath);
            List<FileStatus> files = new ArrayList<>();
            for (FileStatus status : fileStatuses) {
                if (!status.isDirectory()) {
                    files.add(status);
                }
            }

            if (files.isEmpty()) {
                throw new BusinessException("目录下没有文件: " + directoryPath);
            }

            Random random = new Random();
            FileStatus selectedFile = files.get(random.nextInt(files.size()));

            FileDataVO vo = new FileDataVO();
            vo.setFileName(selectedFile.getPath().getName());
            vo.setFilePath(selectedFile.getPath().toString());
            vo.setFileSize(selectedFile.getLen());

            String fileName = selectedFile.getPath().getName();
            String compression = detectCompression(fileName);
            String format = detectFormat(fileName, compression);

            vo.setFileFormat(format);
            vo.setCompression(compression);

            List<Object> records;
            List<String> columns = null;

            switch (format.toUpperCase()) {
                case "PARQUET":
                    ParquetResult parquetResult = readParquetFile(fs, selectedFile.getPath());
                    records = parquetResult.records;
                    columns = parquetResult.columns;
                    break;
                case "ORC":
                    OrcResult orcResult = readOrcFile(fs, selectedFile.getPath());
                    records = orcResult.records;
                    columns = orcResult.columns;
                    break;
                case "CSV":
                    CsvResult csvResult = readCsvFile(fs, selectedFile.getPath(), compression);
                    records = csvResult.records;
                    columns = csvResult.columns;
                    break;
                case "JSON":
                    records = readJsonFile(fs, selectedFile.getPath(), compression);
                    break;
                default:
                    records = readTextFile(fs, selectedFile.getPath(), compression);
                    break;
            }

            vo.setColumns(columns);
            vo.setRecords(records);

            return vo;

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            logger.error("读取文件数据失败: {}", directoryPath, e);
            throw new BusinessException("读取文件数据失败: " + e.getMessage());
        }
    }

    private String detectCompression(String fileName) {
        if (fileName.contains(".")) {
            String[] parts = fileName.split("\\.");
            String lastExt = parts[parts.length - 1].toLowerCase();
            if (COMPRESSION_EXTENSIONS.contains(lastExt)) {
                return lastExt;
            }
        }
        return "NONE";
    }

    private String detectFormat(String fileName, String compression) {
        if (fileName.contains(".")) {
            String[] parts = fileName.split("\\.");
            int formatIndex = "NONE".equals(compression) ? parts.length - 1 : parts.length - 2;
            if (formatIndex > 0) {
                String formatExt = parts[formatIndex].toLowerCase();
                if (TEXT_FORMATS.contains(formatExt)) return "TEXT";
                if (CSV_FORMATS.contains(formatExt)) return "CSV";
                if (JSON_FORMATS.contains(formatExt)) return "JSON";
                if (PARQUET_FORMATS.contains(formatExt)) return "PARQUET";
                if (ORC_FORMATS.contains(formatExt)) return "ORC";
            }
        }
        return "TEXT";
    }

    private InputStream getDecompressedInputStream(org.apache.hadoop.fs.FileSystem fs, Path path, String compression) throws Exception {
        InputStream raw = fs.open(path);
        if ("NONE".equals(compression)) {
            return raw;
        }
        switch (compression.toLowerCase()) {
            case "gz":
            case "gzip":
                return new GZIPInputStream(raw);
            case "bz2":
            case "bzip2":
                org.apache.hadoop.io.compress.CompressionCodec bz2Codec =
                        new org.apache.hadoop.io.compress.BZip2Codec();
                return bz2Codec.createInputStream(raw);
            case "snappy":
                org.apache.hadoop.io.compress.CompressionCodec snappyCodec =
                        new org.apache.hadoop.io.compress.SnappyCodec();
                return snappyCodec.createInputStream(raw);
            case "lz4":
                org.apache.hadoop.io.compress.CompressionCodec lz4Codec =
                        new org.apache.hadoop.io.compress.Lz4Codec();
                return lz4Codec.createInputStream(raw);
            case "zst":
            case "zstd":
                org.apache.hadoop.io.compress.CompressionCodec zstdCodec =
                        new org.apache.hadoop.io.compress.ZStandardCodec();
                return zstdCodec.createInputStream(raw);
            default:
                return raw;
        }
    }

    private List<Object> readTextFile(org.apache.hadoop.fs.FileSystem fs, Path path, String compression) throws Exception {
        List<Object> records = new ArrayList<>();
        try (InputStream is = getDecompressedInputStream(fs, path, compression);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && records.size() < MAX_RECORDS) {
                if (!line.trim().isEmpty()) {
                    records.add(line);
                }
            }
        }
        return records;
    }

    private static class CsvResult {
        List<Object> records;
        List<String> columns;

        CsvResult(List<Object> records, List<String> columns) {
            this.records = records;
            this.columns = columns;
        }
    }

    private CsvResult readCsvFile(org.apache.hadoop.fs.FileSystem fs, Path path, String compression) throws Exception {
        List<Object> records = new ArrayList<>();
        List<String> columns = null;
        try (InputStream is = getDecompressedInputStream(fs, path, compression);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            boolean isFirstLine = true;
            while ((line = reader.readLine()) != null && records.size() < MAX_RECORDS) {
                if (line.trim().isEmpty()) continue;
                String[] values = parseCsvLine(line);
                if (isFirstLine) {
                    columns = Arrays.asList(values);
                    isFirstLine = false;
                } else {
                    Map<String, Object> record = new LinkedHashMap<>();
                    for (int i = 0; i < values.length; i++) {
                        String key = columns != null && i < columns.size() ? columns.get(i) : "col_" + i;
                        record.put(key, values[i]);
                    }
                    records.add(record);
                }
            }
        }
        return new CsvResult(records, columns);
    }

    private String[] parseCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString().trim());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        result.add(current.toString().trim());
        return result.toArray(new String[0]);
    }

    private List<Object> readJsonFile(org.apache.hadoop.fs.FileSystem fs, Path path, String compression) throws Exception {
        List<Object> records = new ArrayList<>();
        try (InputStream is = getDecompressedInputStream(fs, path, compression);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && records.size() < MAX_RECORDS) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    Object parsed = objectMapper.readValue(line, Object.class);
                    records.add(parsed);
                } catch (Exception e) {
                    records.add(line);
                }
            }
        }
        return records;
    }

    private static class ParquetResult {
        List<Object> records;
        List<String> columns;

        ParquetResult(List<Object> records, List<String> columns) {
            this.records = records;
            this.columns = columns;
        }
    }

    private ParquetResult readParquetFile(org.apache.hadoop.fs.FileSystem fs, Path path) throws Exception {
        List<Object> records = new ArrayList<>();
        List<String> columns = new ArrayList<>();

        // 读取 schema 获取列名
        try (ParquetFileReader fileReader = ParquetFileReader.open(HadoopInputFile.fromPath(path, fs.getConf()))) {
            MessageType schema = fileReader.getFooter().getFileMetaData().getSchema();
            for (org.apache.parquet.schema.Type field : schema.getFields()) {
                columns.add(field.getName());
            }
        }

        // 使用 AvroParquetReader 读取数据
        try (ParquetReader<GenericRecord> reader = org.apache.parquet.avro.AvroParquetReader
                .<GenericRecord>builder(path)
                .withConf(fs.getConf())
                .build()) {
            GenericRecord record;
            while ((record = reader.read()) != null && records.size() < MAX_RECORDS) {
                records.add(convertGenericRecordToMap(record));
            }
        }

        return new ParquetResult(records, columns);
    }

    private Map<String, Object> convertGenericRecordToMap(GenericRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (org.apache.avro.Schema.Field field : record.getSchema().getFields()) {
            Object value = record.get(field.name());
            map.put(field.name(), convertAvroValue(value));
        }
        return map;
    }

    private Object convertAvroValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof GenericRecord) {
            return convertGenericRecordToMap((GenericRecord) value);
        }
        if (value instanceof org.apache.avro.util.Utf8) {
            return value.toString();
        }
        if (value instanceof Map) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                map.put(String.valueOf(entry.getKey()), convertAvroValue(entry.getValue()));
            }
            return map;
        }
        if (value instanceof List) {
            List<Object> list = new ArrayList<>();
            for (Object item : (List<?>) value) {
                list.add(convertAvroValue(item));
            }
            return list;
        }
        return value;
    }

    private static class OrcResult {
        List<Object> records;
        List<String> columns;

        OrcResult(List<Object> records, List<String> columns) {
            this.records = records;
            this.columns = columns;
        }
    }

    private OrcResult readOrcFile(org.apache.hadoop.fs.FileSystem fs, Path path) throws Exception {
        List<Object> records = new ArrayList<>();
        List<String> columns = new ArrayList<>();
        Configuration conf = fs.getConf();

        try (Reader reader = OrcFile.createReader(path, OrcFile.readerOptions(conf))) {
            TypeDescription schema = reader.getSchema();
            for (int i = 0; i < schema.getChildren().size(); i++) {
                columns.add(schema.getFieldNames().get(i));
            }

            try (RecordReader rowIterator = reader.rows()) {
                org.apache.orc.storage.ql.exec.vector.VectorizedRowBatch batch =
                        schema.createRowBatch();
                while (rowIterator.nextBatch(batch) && records.size() < MAX_RECORDS) {
                    for (int r = 0; r < batch.size && records.size() < MAX_RECORDS; r++) {
                        Map<String, Object> record = new LinkedHashMap<>();
                        for (int c = 0; c < columns.size(); c++) {
                            Object val = extractOrcValue(batch.cols[c], r, schema.getChildren().get(c));
                            record.put(columns.get(c), val);
                        }
                        records.add(record);
                    }
                }
            }
        }

        return new OrcResult(records, columns);
    }

    private Object extractOrcValue(org.apache.orc.storage.ql.exec.vector.ColumnVector col, int row, TypeDescription type) {
        if (col == null || col.isNull[row]) {
            return null;
        }
        if (col instanceof org.apache.orc.storage.ql.exec.vector.LongColumnVector) {
            long val = ((org.apache.orc.storage.ql.exec.vector.LongColumnVector) col).vector[row];
            switch (type.getCategory()) {
                case BOOLEAN:
                    return val != 0;
                case BYTE:
                case SHORT:
                case INT:
                    return (int) val;
                case LONG:
                case TIMESTAMP:
                case DATE:
                    return val;
                default:
                    return val;
            }
        } else if (col instanceof org.apache.orc.storage.ql.exec.vector.DoubleColumnVector) {
            return ((org.apache.orc.storage.ql.exec.vector.DoubleColumnVector) col).vector[row];
        } else if (col instanceof org.apache.orc.storage.ql.exec.vector.BytesColumnVector) {
            org.apache.orc.storage.ql.exec.vector.BytesColumnVector bytesCol =
                    (org.apache.orc.storage.ql.exec.vector.BytesColumnVector) col;
            return new String(bytesCol.vector[row], bytesCol.start[row], bytesCol.length[row], StandardCharsets.UTF_8);
        } else if (col instanceof org.apache.orc.storage.ql.exec.vector.DecimalColumnVector) {
            return ((org.apache.orc.storage.ql.exec.vector.DecimalColumnVector) col).vector[row].toString();
        }
        return null;
    }
}
