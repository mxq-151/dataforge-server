package com.bigdata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BigDataPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(BigDataPlatformApplication.class, args);
    }
}
