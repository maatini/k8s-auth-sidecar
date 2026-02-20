package space.maatini.sidecar.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.util.PathMatcher;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    @Inject
    SidecarConfig config;

    @Inject
    MeterRegistry meterRegistry;

    // We use a simple ConcurrentHashMap cache for buckets. In a high scale
    // production,
    // a Caffeine or Redis backed ProxyManager would be used.
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private Counter rateLimitExceededCounter;

    @PostConstruct
    void init() {
        rateLimitExceededCounter = Counter.builder("sidecar.rate.limit.exceeded")
                .description("Number of requests rejected due to rate limiting")
                .register(meterRegistry);
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
        String key = resolveClientIp(requestContext);

        AuthContext authContext = (AuthContext) requestContext.getProperty(AUTH_CONTEXT_PROPERTY);
        if (authContext != null && authContext.isAuthenticated()) {
            key = "user:" + authContext.userId();
        } else {
            key = "ip:" + key;
        }

        Bucket bucket = buckets.computeIfAbsent(key, this::createNewBucket);
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (!probe.isConsumed()) {
            LOG.warnf("Rate limit exceeded for %s", key);
            rateLimitExceededCounter.increment();

            // Wait time in seconds for the next token refill
            long waitForRefill = probe.getNanosToWaitForRefill() / 1_000_000_000;

            requestContext.abortWith(Response.status(429)
                    .header("Retry-After", String.valueOf(waitForRefill))
                    .entity(new AuthProxyFilter.ErrorResponse("too_many_requests",
                            "Rate limit exceeded. Try again later."))
                    .build());
        }
    }

    private Bucket createNewBucket(String key) {
        int rps = config.rateLimit().requestsPerSecond();
        int burstSize = config.rateLimit().burstSize();

        Refill refill = Refill.greedy(rps, Duration.ofSeconds(1));
        Bandwidth limit = Bandwidth.builder()
                .capacity(burstSize)
                .refillGreedy(rps, Duration.ofSeconds(1))
                .build();

        return Bucket.builder()
                .addLimit(limit)
                .build();
    }

    private String resolveClientIp(ContainerRequestContext context) {
        String xForwardedFor = context.getHeaderString("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        // Fallback or request IP if directly connected
        // Note: For full IP resolution in JAX-RS without Vert.x request context, we
        // mostly rely on Headers in Sidecars.
        return "unknown";
    }

    private boolean isInternalPath(String path) {
        return path.startsWith("/q/") ||
                path.equals("/health") ||
                path.equals("/metrics") ||
                path.equals("/ready") ||
                path.equals("/live");
    }
}
