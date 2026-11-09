package com.offerchaser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OfferChaser应用程序启动类
 * 自动化求职平台的主入口
 *
 * @version 0.0.1-SNAPSHOT
 */
@SpringBootApplication(scanBasePackages = "com.offerchaser")
@EnableScheduling
@EnableAsync
public class OfferChaserApplication {
    public static void main(String[] args) {
        SpringApplication.run(OfferChaserApplication.class, args);
    }
}