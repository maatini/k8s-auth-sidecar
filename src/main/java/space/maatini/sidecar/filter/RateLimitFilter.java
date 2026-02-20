package space.maatini.sidecar.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Filter that applies rate limiting based on IP and User-ID (if authenticated).
 * Uses Bucket4j for Token-Bucket rate limiting.
 * Runs directly after Authentication but before Proxying (AuthProxyFilter is
 * Priorities.AUTHENTICATION).
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 10)
@ApplicationScoped
public class RateLimitFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(RateLimitFilter.class);
    private static final String AUTH_CONTEXT_PROPERTY = "auth.context";
    private static final int MAX_BUCKETS = 10_000;

    @Inject
    SidecarConfig config;

    @Inject
    MeterRegistry meterRegistry;

    @Context
    HttpServerRequest httpRequest;

    // Eviction: periodically prune expired buckets to prevent unbounded growth.
    // In high-scale production, replace with Caffeine LoadingCache or Bucket4j
    // ProxyManager.
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private Counter rateLimitExceededCounter;
    private ScheduledExecutorService evictionScheduler;

    @PostConstruct
    void init() {
        rateLimitExceededCounter = Counter.builder("sidecar.rate.limit.exceeded")
                .description("Number of requests rejected due to rate limiting")
                .register(meterRegistry);

        // Evict all buckets every 5 minutes to prevent unbounded memory growth
        evictionScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limit-evictor");
            t.setDaemon(true);
            return t;
        });
        evictionScheduler.scheduleAtFixedRate(this::evictBuckets, 5, 5, TimeUnit.MINUTES);
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (!config.rateLimit().enabled()) {
            return;
        }

        String path = requestContext.getUriInfo().getPath();

        // Skip rate limit for internal health checks
        if (isInternalPath(path)) {
            return;
        }

        // Determine the key for rate limiting: User-ID if authenticated, otherwise IP
        // address.
        String key;

        AuthContext authContext = (AuthContext) requestContext.getProperty(AUTH_CONTEXT_PROPERTY);
        if (authContext != null && authContext.isAuthenticated()) {
            key = "user:" + authContext.userId();
        } else {
            key = "ip:" + resolveClientIp(requestContext);
        }

        // Protect against memory exhaustion from too many unique keys
        if (buckets.size() >= MAX_BUCKETS && !buckets.containsKey(key)) {
            LOG.warnf("Rate limit bucket map at capacity (%d), rejecting new key: %s", MAX_BUCKETS, key);
            rateLimitExceededCounter.increment();
            abortWithTooManyRequests(requestContext, 1);
            return;
        }

        Bucket bucket = buckets.computeIfAbsent(key, this::createNewBucket);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (!probe.isConsumed()) {
            LOG.warnf("Rate limit exceeded for %s", key);
            rateLimitExceededCounter.increment();

            long waitForRefill = probe.getNanosToWaitForRefill() / 1_000_000_000;
            abortWithTooManyRequests(requestContext, waitForRefill);
        }
    }

    private void abortWithTooManyRequests(ContainerRequestContext requestContext, long retryAfterSeconds) {
        requestContext.abortWith(Response.status(429)
                .header("Retry-After", String.valueOf(retryAfterSeconds))
                .entity(new AuthProxyFilter.ErrorResponse("too_many_requests",
                        "Rate limit exceeded. Try again later."))
                .build());
    }

    private Bucket createNewBucket(String key) {
        int rps = config.rateLimit().requestsPerSecond();
        int burstSize = config.rateLimit().burstSize();

        Bandwidth limit = Bandwidth.builder()
                .capacity(burstSize)
                .refillGreedy(rps, Duration.ofSeconds(1))
                .build();

        return Bucket.builder()
                .addLimit(limit)
                .build();
    }

    private String resolveClientIp(ContainerRequestContext context) {
        // 1. Check X-Forwarded-For header (reverse proxy scenario)
        String xForwardedFor = context.getHeaderString("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        // 2. Check X-Real-IP header
        String xRealIp = context.getHeaderString("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        // 3. Use Vert.x HttpServerRequest for the actual remote address
        if (httpRequest != null && httpRequest.remoteAddress() != null) {
            return httpRequest.remoteAddress().host();
        }

        return "unknown";
    }

    private void evictBuckets() {
        int size = buckets.size();
        if (size > 0) {
            LOG.debugf("Evicting %d rate-limit buckets", size);
            buckets.clear();
        }
    }

    private boolean isInternalPath(String path) {
        return path.startsWith("/q/") ||
                path.equals("/health") ||
                path.equals("/metrics") ||
                path.equals("/ready") ||
                path.equals("/live");
    }
}
