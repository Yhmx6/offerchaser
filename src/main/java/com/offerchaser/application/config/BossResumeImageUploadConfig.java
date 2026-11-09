package com.offerchaser.application.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.web.servlet.MultipartConfigFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * 图片简历上传限制：单文件 10MB，整个请求 11MB（预留 multipart 边界与请求头开销）。
 * <p>
 * 用 Java 配置而不是要求用户改 application.yaml，保证上传能力开箱可用。
 */
@Configuration
public class BossResumeImageUploadConfig {

    /** 单个文件上限。 */
    private static final DataSize MAX_FILE_SIZE = DataSize.ofMegabytes(10);
    /** 整个 multipart 请求上限，略大于文件上限。 */
    private static final DataSize MAX_REQUEST_SIZE = DataSize.ofMegabytes(11);

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        MultipartConfigFactory factory = new MultipartConfigFactory();
        factory.setMaxFileSize(MAX_FILE_SIZE);
        factory.setMaxRequestSize(MAX_REQUEST_SIZE);
        return factory.createMultipartConfig();
    }
}
