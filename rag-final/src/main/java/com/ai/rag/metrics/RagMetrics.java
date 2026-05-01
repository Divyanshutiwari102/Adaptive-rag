package com.ai.rag.metrics;

import io.micrometer.core.instrument.*;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer metrics for the RAG pipeline — v3.
 *
 * All metric methods used by OpenAiClient, RagPipelineServiceImpl,
 * IngestionServiceImpl, and PartitionMaintenanceService are defined here.
 * Referenced by Prometheus alerts in alerts.yml.
 *
 * Metrics exported:
 *   rag.pipeline.latency                    Timer   (route tag)
 *   rag.pipeline.requests.total             Counter (route tag)
 *   rag.pipeline.grade                      Counter (result=pass|fail)
 *   rag.pipeline.fallback.total             Counter
 *   rag.pipeline.query.rewritten.total      Counter
 *   rag.openai.chat.latency                 Timer
 *   rag.openai.embed.latency                Timer
 *   rag.openai.chat.cost.usd.total          Counter (cumulative USD)
 *   rag.openai.embed.cost.usd.total         Counter (cumulative USD)
 *   rag.openai.errors.total                 Counter (method tag)
 *   rag.ingestion.total                     Counter (status=DONE|FAILED)
 *   rag.ingestion.chunk.count               DistributionSummary
 *   rag.rate_limit.hits                     Counter
 *   rag.rate_limit.redis_failures           Counter
 *   rag.partition.maintenance.last.success  Gauge   (epoch seconds — for alerts)
 */
@Component
public class RagMetrics {

    private final MeterRegistry registry;

    private final Timer   pipelineLatencyIndex;
    private final Timer   pipelineLatencyGeneral;
    private final Counter requestsIndex;
    private final Counter requestsGeneral;
    private final Counter gradePass;
    private final Counter gradeFail;
    private final Counter queryRewritten;
    private final Counter fallbackServed;
    private final Counter ingestionDone;
    private final Counter ingestionFailed;
    private final DistributionSummary chunkCountSummary;
    private final Timer   chatLatencyTimer;
    private final Timer   embedLatencyTimer;
    private final Counter chatCostCounter;
    private final Counter embedCostCounter;

    // FIX #10: Gauge for partition maintenance liveness alert
    private final AtomicLong partitionMaintenanceLastSuccess = new AtomicLong(0);

    public RagMetrics(MeterRegistry registry) {
        this.registry = registry;

        pipelineLatencyIndex = Timer.builder("rag.pipeline.latency")
                .tag("route", "INDEX").publishPercentiles(0.50, 0.95, 0.99).register(registry);
        pipelineLatencyGeneral = Timer.builder("rag.pipeline.latency")
                .tag("route", "GENERAL").publishPercentiles(0.50, 0.95, 0.99).register(registry);

        requestsIndex   = Counter.builder("rag.pipeline.requests.total").tag("route", "INDEX").register(registry);
        requestsGeneral = Counter.builder("rag.pipeline.requests.total").tag("route", "GENERAL").register(registry);

        gradePass = Counter.builder("rag.pipeline.grade").tag("result", "pass").register(registry);
        gradeFail = Counter.builder("rag.pipeline.grade").tag("result", "fail").register(registry);

        queryRewritten = Counter.builder("rag.pipeline.query.rewritten.total").register(registry);
        fallbackServed = Counter.builder("rag.pipeline.fallback.total").register(registry);

        ingestionDone   = Counter.builder("rag.ingestion.total").tag("status", "DONE").register(registry);
        ingestionFailed = Counter.builder("rag.ingestion.total").tag("status", "FAILED").register(registry);

        chunkCountSummary = DistributionSummary.builder("rag.ingestion.chunk.count")
                .publishPercentiles(0.50, 0.95).register(registry);

        chatLatencyTimer  = Timer.builder("rag.openai.chat.latency")
                .publishPercentiles(0.50, 0.95, 0.99).register(registry);
        embedLatencyTimer = Timer.builder("rag.openai.embed.latency")
                .publishPercentiles(0.50, 0.95, 0.99).register(registry);

        chatCostCounter  = Counter.builder("rag.openai.chat.cost.usd.total")
                .description("Cumulative OpenAI chat cost in USD").register(registry);
        embedCostCounter = Counter.builder("rag.openai.embed.cost.usd.total")
                .description("Cumulative OpenAI embed cost in USD").register(registry);

        // FIX #10: Expose partition maintenance timestamp as a Gauge for liveness alerting
        Gauge.builder("rag.partition.maintenance.last.success", partitionMaintenanceLastSuccess, AtomicLong::get)
                .description("Unix epoch seconds of last successful partition maintenance run")
                .register(registry);
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────

    public void recordPipelineLatency(String route, long latencyMs) {
        Timer t = "GENERAL".equals(route) ? pipelineLatencyGeneral : pipelineLatencyIndex;
        t.record(latencyMs, TimeUnit.MILLISECONDS);
    }

    public void incrementRequests(String route) {
        if ("GENERAL".equals(route)) requestsGeneral.increment();
        else requestsIndex.increment();
    }

    public void recordGrade(boolean passed) {
        if (passed) gradePass.increment(); else gradeFail.increment();
    }

    public void incrementQueryRewritten() { queryRewritten.increment(); }
    public void incrementFallbackServed() { fallbackServed.increment(); }

    // ── Ingestion ─────────────────────────────────────────────────────────────

    public void recordIngestionDone(int chunkCount) {
        ingestionDone.increment();
        chunkCountSummary.record(chunkCount);
    }

    public void recordIngestionFailed() { ingestionFailed.increment(); }

    // ── OpenAI ────────────────────────────────────────────────────────────────

    public void recordChatLatency(long ms)    { chatLatencyTimer.record(ms, TimeUnit.MILLISECONDS); }
    public void recordEmbedLatency(long ms)   { embedLatencyTimer.record(ms, TimeUnit.MILLISECONDS); }
    public void recordChatCost(double usd)    { chatCostCounter.increment(usd); }
    public void recordEmbedCost(double usd)   { embedCostCounter.increment(usd); }

    public void incrementOpenAiError(String method) {
        Counter.builder("rag.openai.errors.total")
                .tag("method", method)
                .register(registry)
                .increment();
    }

    // ── Partition maintenance ─────────────────────────────────────────────────

    /** Call after each successful partition maintenance run. Used by liveness alert. */
    public void recordPartitionMaintenanceSuccess() {
        partitionMaintenanceLastSuccess.set(System.currentTimeMillis() / 1000L);
    }
}
