package com.ai.rag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * PROBLEM THIS FILE SOLVES:
 *
 * Spring MVC handles Flux<ServerSentEvent> by converting it to an async servlet
 * response via DeferredResult. When Tomcat hands off to the async thread, it
 * re-enters the security filter chain on the NEW thread. The SecurityContext
 * lives in a ThreadLocal — it is NOT inherited by child threads by default.
 *
 * The sequence that produces your errors:
 *   1. Request arrives → JwtAuthenticationFilter runs → sets SecurityContext on Thread A
 *   2. Controller returns Flux<SSE> → Spring starts async dispatch on Thread B
 *   3. Thread B has no SecurityContext → AuthorizationFilter sees anonymous user
 *   4. First few SSE bytes already written (response committed)
 *   5. AuthorizationFilter throws AccessDeniedException
 *   6. Spring Security cannot write 403 (response committed) → logs the "already committed" error
 *   7. Tomcat kills the connection → browser gets ERR_INCOMPLETE_CHUNKED_ENCODING
 *
 * THE FIX:
 *   DelegatingSecurityContextAsyncTaskExecutor wraps every Runnable submitted to
 *   the thread pool and calls SecurityContextHolder.setContext(capturedContext)
 *   before the task runs on the new thread. This is the official Spring Security
 *   solution documented in the reference manual section "Concurrency Support".
 *
 * SECONDARY FIX:
 *   Replaces SimpleAsyncTaskExecutor (which creates a new thread per task, unbounded)
 *   with a ThreadPoolTaskExecutor. Eliminates the production WARNING:
 *   "SimpleAsyncTaskExecutor is not suitable for production use under load"
 */
@Configuration
public class WebMvcAsyncConfig implements WebMvcConfigurer {

    /**
     * Production thread pool that propagates SecurityContext to every async worker.
     *
     * Pool sizing rationale:
     *   corePoolSize 10  — handles steady streaming load without thread creation overhead
     *   maxPoolSize  20  — allows burst of 20 concurrent SSE streams
     *   queueCapacity 50 — queues overflow rather than rejecting; SSE streams complete
     *                      within the 90s timeout so queue depth stays low in practice
     *
     * DelegatingSecurityContextAsyncTaskExecutor is the key wrapper.
     * It captures SecurityContextHolder.getContext() at task-submission time
     * and restores it on the worker thread before execution.
     */
    @Bean(name = "mvcAsyncTaskExecutor")
    public AsyncTaskExecutor mvcAsyncTaskExecutor() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(10);
        pool.setMaxPoolSize(20);
        pool.setQueueCapacity(50);
        pool.setThreadNamePrefix("rag-stream-");
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);
        pool.initialize();

        // This is the line that fixes AccessDeniedException on async dispatch.
        // It copies the SecurityContext from the submitting thread to the worker thread.
        return new DelegatingSecurityContextAsyncTaskExecutor(pool);
    }

    /**
     * Register our executor as the async handler for all Spring MVC async results:
     * DeferredResult, Callable, Flux<SSE>, SseEmitter, StreamingResponseBody.
     *
     * defaultTimeout 90_000ms:
     *   Groq timeout is 45s + 30s buffer in the Flux pipeline = 75s max.
     *   90s gives a clean upper bound. When exceeded, Spring sends an async
     *   timeout error and releases the thread — no indefinite thread leaks.
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(90_000L);
        configurer.setTaskExecutor(mvcAsyncTaskExecutor());
    }
}