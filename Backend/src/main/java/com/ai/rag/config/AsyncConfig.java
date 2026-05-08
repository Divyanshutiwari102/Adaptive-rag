package com.ai.rag.config;

import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async configuration for background ingestion.
 *
 * Dedicated thread pool "ingestionExecutor":
 *  - core 4 / max 16 threads (CPU-bound embedding parse is light; I/O-bound on OpenAI)
 *  - queue 200 — absorbs bursts without rejecting
 *  - rejection: CallerRunsPolicy would block the request thread — we prefer
 *    TaskRejectedException surfaced as 503, handled in DocumentController.
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Bean(name = "ingestionExecutor")
    public Executor ingestionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("rag-ingest-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                // Ingestion failures are persisted on the Document entity; log here for ops alerting
                org.slf4j.LoggerFactory.getLogger(AsyncConfig.class)
                        .error("[Async] Uncaught exception in {}: {}", method.getName(), ex.getMessage(), ex);
    }
}
