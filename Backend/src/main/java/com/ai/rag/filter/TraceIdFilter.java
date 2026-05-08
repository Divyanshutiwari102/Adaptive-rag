package com.ai.rag.filter;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that injects a trace ID into every request.
 *
 * Priority: runs first (HIGHEST_PRECEDENCE) so all downstream components —
 * including Spring Security filters — log with the trace ID attached.
 *
 * Behaviour:
 *  1. Reads X-Trace-Id header from the request (set by API gateway / client).
 *  2. If absent, generates a new UUID.
 *  3. Puts it in SLF4J MDC as "traceId" — picked up by the logging pattern:
 *       "%d [%thread] [%X{traceId}] %-5level %logger - %msg%n"
 *  4. Echoes it on the response as X-Trace-Id so clients can correlate.
 *  5. Cleans MDC after the request to prevent thread-pool leakage.
 *
 * With this in place, grepping logs for a single traceId gives the full
 * request flow: auth filter → rate limiter → RAG pipeline → OpenAI calls.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter implements Filter {

    private static final String TRACE_HEADER = "X-Trace-Id";
    private static final String MDC_KEY      = "traceId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {

        HttpServletRequest  httpReq  = (HttpServletRequest)  request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        String traceId = httpReq.getHeader(TRACE_HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }

        MDC.put(MDC_KEY, traceId);
        httpResp.setHeader(TRACE_HEADER, traceId);

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);   // critical: ThreadLocal cleanup
        }
    }
}
