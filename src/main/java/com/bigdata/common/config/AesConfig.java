package com.bigdata.common.config;

import com.bigdata.common.utils.AesUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "aes")
public class AesConfig {

    private String key;

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    @Bean
    public AesUtils aesUtils() {
        return new AesUtils(key);
    }
}
