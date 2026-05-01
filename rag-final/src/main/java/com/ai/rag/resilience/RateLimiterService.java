package com.ai.rag.resilience;

import com.ai.rag.config.RateLimitConfig;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * UPDATED — FIX #5: Replace in-memory rate limiter with Redis Bucket4j.
 *
 * PROBLEM: The original ConcurrentHashMap<String, Bucket> is process-local.
 * In a horizontally-scaled deployment (2+ app pods), each pod maintains its own
 * counter. A user sending 20 requests round-robined across 3 pods can send 60 before
 * being rate-limited — 3× the intended cap.
 *
 * FIX: Use Bucket4j's Lettuce-based distributed ProxyManager. Buckets are stored in
 * Redis with atomic CAS operations (compare-and-swap), so the rate limit is enforced
 * correctly regardless of how many app instances are running.
 *
 * FAIL-OPEN: If Redis is unavailable, tryConsume() returns true (allow) and increments
 * a metric. This prevents Redis outages from taking down the API. Rate limiting is
 * degraded but the service stays up.
 *
 * KEY FORMAT: "rl:{endpoint}:{userId}" — scoped so ask and ingest limits are independent.
 */
@Service
@Slf4j
public class RateLimiterService {

    private final RateLimitConfig          config;
    private final ProxyManager<byte[]>     proxyManager;
    private final Counter                  redisFailureCounter;

    public RateLimiterService(RateLimitConfig config,
                              RedisClient redisClient,
                              MeterRegistry meterRegistry) {
        this.config = config;

        // Build a dedicated Lettuce connection for Bucket4j (byte[] codec required)
        StatefulRedisConnection<byte[], byte[]> connection =
                redisClient.connect(ByteArrayCodec.INSTANCE);
        this.proxyManager = LettuceBasedProxyManager.builderFor(connection).build();

        this.redisFailureCounter = Counter.builder("rag.rate_limit.redis_failures")
                .description("Rate limiter Redis failures (fail-open events)")
                .register(meterRegistry);
    }

    /**
     * Attempt to consume one token for the given endpoint + user.
     *
     * @return true  = request is allowed (or Redis is down — fail-open)
     *         false = rate limit exceeded
     */
    public boolean tryConsume(String endpoint, String userId) {
        byte[] key = ("rl:" + endpoint + ":" + userId).getBytes();
        Supplier<BucketConfiguration> configSupplier = () -> buildConfig(endpoint);
        try {
            Bucket bucket = proxyManager.builder().build(key, configSupplier);
            boolean allowed = bucket.tryConsume(1);
            if (!allowed) {
                log.warn("[RateLimit] Limit exceeded endpoint={} user={}", endpoint, userId);
            }
            return allowed;
        } catch (Exception e) {
            // FAIL-OPEN: Redis down → allow the request, log metric for alerting
            redisFailureCounter.increment();
            log.warn("[RateLimit] Redis unavailable, failing open for endpoint={} user={}: {}",
                    endpoint, userId, e.getMessage());
            return true;
        }
    }

    private BucketConfiguration buildConfig(String endpoint) {
        RateLimitConfig.LimitSpec spec = "ingest".equals(endpoint)
                ? config.getIngest()
                : config.getAsk();
        Bandwidth bw = Bandwidth.classic(
                spec.getCapacity(),
                Refill.greedy(spec.getRefillTokens(), Duration.ofSeconds(spec.getRefillSeconds())));
        return BucketConfiguration.builder().addLimit(bw).build();
    }
}
