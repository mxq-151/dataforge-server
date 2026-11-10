package com.bigdata.modules.integration.vo;

import java.util.List;

/**
 * 数据源类型 + 该类型下源端/目标端需要填写的参数描述。
 * 前端据此动态渲染表单，新增数据源类型时页面无需改动。
 */
public class DataSourceTypeVO {

    private String code;
    private String label;
    private boolean streamSource;
    private boolean streamSink;
    private List<FieldVO> readFields;
    private List<FieldVO> writeFields;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public boolean isStreamSource() {
        return streamSource;
    }

    public void setStreamSource(boolean streamSource) {
        this.streamSource = streamSource;
    }

    public boolean isStreamSink() {
        return streamSink;
    }

    public void setStreamSink(boolean streamSink) {
        this.streamSink = streamSink;
    }

    public List<FieldVO> getReadFields() {
        return readFields;
    }

    public void setReadFields(List<FieldVO> readFields) {
        this.readFields = readFields;
    }

    public List<FieldVO> getWriteFields() {
        return writeFields;
    }

    public void setWriteFields(List<FieldVO> writeFields) {
        this.writeFields = writeFields;
    }

    public static class FieldVO {
        private String key;
        private String label;
        private boolean required;
        private String placeholder;
        private String type;
        /** type=select 时的可选值 */
        private List<String> options;

        public FieldVO() {
        }

        public FieldVO(String key, String label, boolean required, String placeholder, String type) {
            this(key, label, required, placeholder, type, List.of());
        }

        public FieldVO(String key, String label, boolean required, String placeholder, String type,
                       List<String> options) {
            this.key = key;
            this.label = label;
            this.required = required;
            this.placeholder = placeholder;
            this.type = type;
            this.options = options == null ? List.of() : options;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public boolean isRequired() {
            return required;
        }

        public void setRequired(boolean required) {
            this.required = required;
        }

        public String getPlaceholder() {
            return placeholder;
        }

        public void setPlaceholder(String placeholder) {
            this.placeholder = placeholder;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public List<String> getOptions() {
            return options;
        }

        public void setOptions(List<String> options) {
            this.options = options;
        }
    }
}
