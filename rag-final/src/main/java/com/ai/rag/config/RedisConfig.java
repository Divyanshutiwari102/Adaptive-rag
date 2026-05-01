package com.ai.rag.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * NEW — FIX #5: Provides a Lettuce RedisClient for Bucket4j distributed rate limiting.
 *
 * Spring Boot auto-configures a Lettuce connection for Spring Data Redis, but Bucket4j
 * needs a raw RedisClient with a byte[] codec — it cannot reuse the Spring Data connection
 * because Spring Data uses String serialization. We create a separate minimal client here.
 */
@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Bean(destroyMethod = "shutdown")
    public RedisClient bucket4jRedisClient() {
        RedisURI.Builder builder = RedisURI.builder()
                .withHost(host)
                .withPort(port);
        if (password != null && !password.isBlank()) {
            builder.withPassword(password.toCharArray());
        }
        return RedisClient.create(builder.build());
    }
}
