package com.ai.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "storage")
@Data
public class StorageProperties {
    private String provider = "s3";   // s3 | local
    private S3Config s3 = new S3Config();

    @Data
    public static class S3Config {
        private String bucket = "adaptive-rag-files";
        private String region = "us-east-1";
        private String endpointOverride = "";
    }
}
