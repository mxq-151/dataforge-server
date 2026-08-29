package com.bigdata.common.test;

import com.bigdata.common.exception.GlobalExceptionHandler;
import com.bigdata.modules.devops.converter.StringToDatasourceTypeConverter;
import com.bigdata.modules.knowledge.converter.StringToDocTypeConverter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 构建与生产配置对齐的 {@link MockMvc}，用于接口返回体（JSON）的契约回归。
 * <p>
 * 刻意不启动 Spring 容器、不连数据库：回归关注点是「参数绑定 + Jackson 序列化」这两段
 * 决定接口返回值的链路，与 {@code application.yml} 中的 jackson 配置、
 * {@code WebMvcConfig#addFormatters} 注册的转换器保持一致即可。
 * </p>
 */
public final class ContractMockMvcSupport {

    private ContractMockMvcSupport() {
    }

    /** 与 {@code application.yml} 中 spring.jackson 配置保持一致的 ObjectMapper */
    public static ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mapper.configure(JsonGenerator.Feature.ESCAPE_NON_ASCII, false);
        return mapper;
    }

    /** 与 {@code WebMvcConfig#addFormatters} 保持一致的转换服务 */
    public static DefaultFormattingConversionService conversionService() {
        DefaultFormattingConversionService service = new DefaultFormattingConversionService();
        service.addConverter(new StringToDocTypeConverter());
        service.addConverter(new StringToDatasourceTypeConverter());
        return service;
    }

    /**
     * @param controllers 待测控制器，全局异常处理 {@link GlobalExceptionHandler} 会自动挂载
     */
    public static MockMvc mockMvc(Object... controllers) {
        return MockMvcBuilders.standaloneSetup(controllers)
                .setConversionService(conversionService())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }
}
