package com.bigdata.modules.integration.service.submit;

import com.bigdata.common.exception.BusinessException;
import com.bigdata.modules.integration.enums.DataSourceKind;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文件系统目标端（HDFS / S3）的文件格式与压缩参数。
 *
 * <p>Flink filesystem connector 的压缩选项名随格式变化：
 * parquet 用 {@code parquet.compression}、orc 用 {@code orc.compress}、
 * avro 用 {@code avro.codec}；csv / json 本身不支持压缩。
 * 这里统一做格式白名单、压缩算法兼容性校验与最终选项映射，
 * 保存任务时与生成 SQL 时使用同一套规则。</p>
 *
 * <p>压缩算法留空表示沿用格式默认值：parquet=snappy、orc=zlib、
 * avro/csv/json=不压缩。</p>
 */
public final class FileSinkOptions {

    /** 支持的文件格式 */
    public static final List<String> FORMATS = List.of("parquet", "orc", "avro", "csv", "json");

    private static final List<String> PARQUET_CODECS =
            List.of("none", "snappy", "gzip", "lzo", "brotli", "lz4", "zstd");
    private static final List<String> ORC_CODECS =
            List.of("none", "zlib", "snappy", "lzo", "lz4");
    private static final List<String> AVRO_CODECS =
            List.of("none", "deflate", "snappy", "bzip2", "xz", "zstandard");

    private FileSinkOptions() {
    }

    /** 保存任务时的校验入口：非文件系统目标端直接跳过 */
    public static void validate(DataSourceKind kind, Map<String, Object> config) {
        if (kind == DataSourceKind.HDFS || kind == DataSourceKind.S3) {
            resolve(config);
        }
    }

    /**
     * 解析为 Flink 建表 WITH 选项。
     *
     * @return format 与压缩选项；压缩留空时只返回 format，交给 Flink 使用格式默认值
     */
    public static Map<String, String> resolve(Map<String, Object> config) {
        String format = str(config, "format").toLowerCase();
        if (format.isEmpty()) {
            format = "parquet";
        }
        if (!FORMATS.contains(format)) {
            throw new BusinessException(400, "不支持的文件格式: " + format
                    + "，可选: " + String.join(" / ", FORMATS));
        }

        Map<String, String> options = new LinkedHashMap<>();
        options.put("format", format);

        String compression = str(config, "compression").toLowerCase();
        if (compression.isEmpty() || "auto".equals(compression)) {
            return options;
        }
        switch (format) {
            case "parquet" -> options.put("parquet.compression",
                    mapCodec(compression, PARQUET_CODECS, format, "parquet.compression", "uncompressed"));
            case "orc" -> options.put("orc.compress",
                    mapCodec(compression, ORC_CODECS, format, "orc.compress", "NONE")
                            .toUpperCase(Locale.ROOT));
            case "avro" -> options.put("avro.codec", avroCodec(compression));
            case "csv", "json" -> {
                // csv / json 本身不支持压缩，只允许显式选择 none
                if (!"none".equals(compression) && !"uncompressed".equals(compression)) {
                    throw new BusinessException(400, "文件格式 " + format
                            + " 不支持压缩算法 " + compression + "，请选择 none 或留空使用默认值");
                }
            }
            default -> throw new BusinessException(400, "不支持的文件格式: " + format
                    + "，可选: " + String.join(" / ", FORMATS));
        }
        return options;
    }

    private static String mapCodec(String compression, List<String> supported,
                                   String format, String optionKey, String noneValue) {
        if ("none".equals(compression) || "uncompressed".equals(compression)) {
            return noneValue;
        }
        if (!supported.contains(compression)) {
            throw new BusinessException(400, "文件格式 " + format + " 不支持压缩算法 " + compression
                    + "，可选: " + String.join(" / ", supported)
                    + "（见 " + optionKey + "）");
        }
        return compression;
    }

    private static String avroCodec(String compression) {
        if ("none".equals(compression) || "uncompressed".equals(compression)) {
            return "null";
        }
        if ("zstd".equals(compression)) {
            return "zstandard";
        }
        if (!AVRO_CODECS.contains(compression)) {
            throw new BusinessException(400, "文件格式 avro 不支持压缩算法 " + compression
                    + "，可选: " + String.join(" / ", AVRO_CODECS) + "（见 avro.codec）");
        }
        return compression;
    }

    private static String str(Map<String, Object> config, String key) {
        if (config == null) {
            return "";
        }
        Object value = config.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }
}
