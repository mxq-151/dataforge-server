package com.bigdata.modules.knowledge.converter;

import com.bigdata.modules.knowledge.enums.DocType;
import org.springframework.core.convert.converter.Converter;

public class StringToDocTypeConverter implements Converter<String, DocType> {

    @Override
    public DocType convert(String source) {
        if (source == null || source.isEmpty()) {
            return null;
        }
        return DocType.fromCode(source.trim());
    }
}
