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

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
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

    // RATE-LIMIT SECURITY FIX – Gemini 3 Flash P0.3
    private Cache<String, Bucket> buckets;

    private Counter rateLimitExceededCounter;

    @PostConstruct
    void init() {
        rateLimitExceededCounter = Counter.builder("sidecar.rate.limit.exceeded")
                .description("Number of requests rejected due to rate limiting")
                .register(meterRegistry);

        // RATE-LIMIT SECURITY FIX – Gemini 3 Flash P0.3
        // Caffeine cache to prevent memory leaks with bounded size and expiration
        buckets = Caffeine.newBuilder()
                .maximumSize(MAX_BUCKETS)
                .expireAfterAccess(15, TimeUnit.MINUTES) // Free memory after periods of inactivity
                .build();
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (!config.rateLimit().enabled()) {
            return;
        }

        String path = requestContext.getUriInfo().getPath();

        // Skip rate limit for internal health checks
        if (space.maatini.sidecar.util.PathMatcher.isInternalPath(path)) {
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
        // using Caffeine size bounds
        Bucket bucket = buckets.get(key, this::createNewBucket);
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
        String remoteAddress = "unknown";
        if (httpRequest != null && httpRequest.remoteAddress() != null) {
            remoteAddress = httpRequest.remoteAddress().host();
        }

        List<String> trustedProxies = config.rateLimit().trustedProxies();
        boolean isTrustedProxy = trustedProxies != null && trustedProxies.contains(remoteAddress);

        if (isTrustedProxy) {
            // 1. Check X-Forwarded-For header (reverse proxy scenario)
            String xForwardedFor = context.getHeaderString("X-Forwarded-For");
            if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
                // Take the leftmost IP (the original client IP claimed by the proxy chain)
                return xForwardedFor.split(",")[0].trim();
            }

            // 2. Check X-Real-IP header
            String xRealIp = context.getHeaderString("X-Real-IP");
            if (xRealIp != null && !xRealIp.isEmpty()) {
                return xRealIp;
            }
        }

        // 3. Fallback to the real remote IP if not a trusted proxy, or no spoofed
        // headers
        return remoteAddress;
    }

}
