package com.flashmall;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.flashmall.**.mapper")
@EnableScheduling
public class FlashMallApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlashMallApplication.class, args);
    }
}
