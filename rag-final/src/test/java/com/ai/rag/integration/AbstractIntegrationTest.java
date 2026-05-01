package com.ai.rag.integration;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for all integration tests.
 *
 * Uses a real PostgreSQL container (with pgvector extension) via Testcontainers.
 * The container is shared across all subclasses in the test run — started once,
 * reused, torn down after all tests complete.
 *
 * IMPORTANT: The Docker image used here is ankane/pgvector which ships with
 * the pgvector extension pre-installed. In CI, ensure Docker is available.
 *
 * Spring properties are injected dynamically from the container's runtime ports,
 * so tests are fully isolated from local Postgres installations.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("ankane/pgvector:latest")
            .withDatabaseName("adaptive_rag_test")
            .withUsername("test")
            .withPassword("test")
            .withReuse(true);   // reuse container across test classes in same JVM

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // Disable Redis for integration tests — use simple in-memory cache
        registry.add("spring.cache.type", () -> "simple");

        // Use test JWT secret (valid Base64, 32+ bytes)
        registry.add("jwt.secret", () -> "dGVzdC1zZWNyZXQta2V5LXRoYXQtaXMtbG9uZy1lbm91Z2gtZm9yLUhTMjU2");
        registry.add("openai.api-key", () -> "test-key-not-used");
    }
}
