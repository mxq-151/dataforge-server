package com.bigdata.common.test;

import com.bigdata.common.config.WebMvcConfig;
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
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

/**
 * 构建用于接口返回体（JSON）契约回归的 {@link MockMvc}。
 * <p>
 * 刻意不启动 Spring 容器、不连数据库：回归关注点是「参数绑定 + Jackson 序列化」这两段
 * 决定接口返回值的链路。
 * </p>
 *
 * <h3>两套 ObjectMapper 的取舍（写新测试前必读）</h3>
 * <ul>
 *   <li>{@link #objectMapper()} / {@link #mockMvc(Object...)}：按 {@code application.yml} 的
 *       {@code spring.jackson} 配置手工构造（<b>SNAKE_CASE</b> + NON_NULL）。它描述的是
 *       <b>配置文件语义</b>，用于 {@code MetadataApiContractTest} /
 *       {@code DatasourceApiContractTest} 两个历史用例。</li>
 *   <li>{@link #productionObjectMapper()} / {@link #productionMockMvc(Object...)}：
 *       直接复用生产 Bean 工厂 {@link WebMvcConfig#objectMapper()}，即
 *       <b>camelCase</b>、不裁剪 null。见服务端 README「注意事项 4」：yml 中的
 *       {@code property-naming-strategy: SNAKE_CASE} 被 {@code WebMvcConfig} 的
 *       {@code @Primary ObjectMapper} 覆盖，<b>实际不生效</b>，线上返回的字段名是 camelCase，
 *       前端 {@code src/api/*.ts} 的类型定义据此编写。</li>
 * </ul>
 * <p>
 * 因此：<b>新增接口契约测试请用 {@code production*} 系列</b>，否则断言的是线上并不存在的
 * snake_case 字段名，会写出「假绿」的契约用例。
 * </p>
 */
public final class ContractMockMvcSupport {

    private ContractMockMvcSupport() {
    }

    /**
     * 与生产<b>真实</b>序列化行为一致的 ObjectMapper —— 直接取生产 Bean 工厂
     * {@link WebMvcConfig#objectMapper()} 的产物（camelCase、不裁剪 null、中文不转义）。
     * 新增契约测试应优先使用本方法。
     */
    public static ObjectMapper productionObjectMapper() {
        return new WebMvcConfig().objectMapper();
    }

    /** 使用生产真实 ObjectMapper 的 MockMvc；语义见 {@link #productionObjectMapper()} */
    public static MockMvc productionMockMvc(Object... controllers) {
        return MockMvcBuilders.standaloneSetup(controllers)
                .setConversionService(conversionService())
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new MappingJackson2HttpMessageConverter(productionObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 与 {@code application.yml} 中 spring.jackson 配置保持一致的 ObjectMapper。
     * <p><b>注意</b>：该配置在生产中不生效（见类注释），新测试请改用
     * {@link #productionObjectMapper()}。</p>
     */
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
