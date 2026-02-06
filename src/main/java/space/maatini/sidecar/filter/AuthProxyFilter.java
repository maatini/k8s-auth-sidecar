package space.maatini.sidecar.filter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.mutiny.core.buffer.Buffer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.model.PolicyDecision;
import space.maatini.sidecar.service.AuthenticationService;
import space.maatini.sidecar.service.PolicyService;
import space.maatini.sidecar.service.ProxyService;
import space.maatini.sidecar.service.RolesService;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Main request filter that intercepts all incoming requests and performs:
 * 1. Authentication (via Quarkus OIDC)
 * 2. Authorization (via OPA policies)
 * 3. Proxying to the backend container
 */
@Provider
@Priority(Priorities.AUTHENTICATION)
@ApplicationScoped
public class AuthProxyFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(AuthProxyFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String AUTH_CONTEXT_PROPERTY = "auth.context";

    @Inject
    SidecarConfig config;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    AuthenticationService authenticationService;

    @Inject
    RolesService rolesService;

    @Inject
    PolicyService policyService;

    @Inject
    ProxyService proxyService;

    @Inject
    MeterRegistry meterRegistry;

    @Context
    UriInfo uriInfo;

    @Context
    HttpServerRequest httpRequest;

    private Counter authSuccessCounter;
    private Counter authFailureCounter;
    private Counter authzAllowCounter;
    private Counter authzDenyCounter;
    private Timer authTimer;

    @PostConstruct
    void init() {
        authSuccessCounter = Counter.builder("sidecar.auth.success")
            .description("Successful authentications")
            .register(meterRegistry);
        
        authFailureCounter = Counter.builder("sidecar.auth.failure")
            .description("Failed authentications")
            .register(meterRegistry);
        
        authzAllowCounter = Counter.builder("sidecar.authz.allow")
            .description("Allowed authorization decisions")
            .register(meterRegistry);
        
        authzDenyCounter = Counter.builder("sidecar.authz.deny")
            .description("Denied authorization decisions")
            .register(meterRegistry);
        
        authTimer = Timer.builder("sidecar.auth.duration")
            .description("Authentication duration")
            .register(meterRegistry);
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        long startTime = System.nanoTime();
        String path = requestContext.getUriInfo().getPath();
        String method = requestContext.getMethod();
        
        // Ensure request has an ID for tracing
        ensureRequestId(requestContext);

        // Skip auth for public paths
        if (isPublicPath(path)) {
            LOG.debugf("Skipping auth for public path: %s", path);
            return;
        }

        // Skip auth for internal Quarkus paths
        if (isInternalPath(path)) {
            LOG.debugf("Skipping auth for internal path: %s", path);
            return;
        }

        // Check if auth is enabled
        if (!config.auth().enabled()) {
            LOG.debug("Authentication is disabled");
            return;
        }

        try {
            // Extract authentication context
            AuthContext authContext = authenticationService.extractAuthContext(securityIdentity);

            if (!authContext.isAuthenticated()) {
                LOG.warnf("Authentication failed for request: %s %s", method, path);
                authFailureCounter.increment();
                abortWithUnauthorized(requestContext, "Authentication required");
                return;
            }

            authSuccessCounter.increment();
            LOG.debugf("Authenticated user: %s", authContext.userId());

            // Store auth context for later use
            requestContext.setProperty(AUTH_CONTEXT_PROPERTY, authContext);

            // Enrich with roles from external service (blocking for filter)
            AuthContext enrichedContext = rolesService.enrichWithRoles(authContext)
                .await().atMost(java.time.Duration.ofSeconds(5));

            // Perform authorization check
            if (config.authz().enabled()) {
                Map<String, String> headers = extractHeaders(requestContext);
                Map<String, String> queryParams = extractQueryParams(requestContext);

                PolicyDecision decision = policyService.evaluate(
                    enrichedContext, method, path, headers, queryParams
                ).await().atMost(java.time.Duration.ofSeconds(5));

                if (!decision.allowed()) {
                    LOG.warnf("Authorization denied for user %s on %s %s: %s",
                        enrichedContext.userId(), method, path, decision.reason());
                    authzDenyCounter.increment();
                    abortWithForbidden(requestContext, decision);
                    return;
                }

                authzAllowCounter.increment();
                LOG.debugf("Authorization allowed for user %s on %s %s",
                    enrichedContext.userId(), method, path);
            }

            // Update stored context with enriched version
            requestContext.setProperty(AUTH_CONTEXT_PROPERTY, enrichedContext);

        } catch (Exception e) {
            LOG.errorf(e, "Error during authentication/authorization");
            abortWithError(requestContext, "Internal authentication error");
        } finally {
            long duration = System.nanoTime() - startTime;
            authTimer.record(duration, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Ensures the request has a unique ID for tracing.
     */
    private void ensureRequestId(ContainerRequestContext requestContext) {
        String requestId = requestContext.getHeaderString(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
            // Note: We can't add headers to the incoming request, but we can track it
            requestContext.setProperty(REQUEST_ID_HEADER, requestId);
        }
    }

    /**
     * Checks if a path is public and doesn't require authentication.
     */
    private boolean isPublicPath(String path) {
        List<String> publicPaths = config.auth().publicPaths();
        if (publicPaths == null) {
            return false;
        }

        for (String publicPath : publicPaths) {
            if (matchesPath(path, publicPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a path is an internal Quarkus path.
     */
    private boolean isInternalPath(String path) {
        return path.startsWith("/q/") || 
               path.equals("/health") ||
               path.equals("/metrics") ||
               path.equals("/ready") ||
               path.equals("/live");
    }

    /**
     * Simple path matching with wildcard support.
     */
    private boolean matchesPath(String path, String pattern) {
        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            return path.startsWith(prefix) || path.equals(prefix.substring(0, prefix.length() > 0 ? prefix.length() - 1 : 0));
        } else if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.startsWith(prefix);
        }
        return path.equals(pattern);
    }

    /**
     * Extracts headers from the request context.
     */
    private Map<String, String> extractHeaders(ContainerRequestContext requestContext) {
        Map<String, String> headers = new HashMap<>();
        for (String headerName : requestContext.getHeaders().keySet()) {
            headers.put(headerName, requestContext.getHeaderString(headerName));
        }
        return headers;
    }

    /**
     * Extracts query parameters from the request context.
     */
    private Map<String, String> extractQueryParams(ContainerRequestContext requestContext) {
        Map<String, String> params = new HashMap<>();
        requestContext.getUriInfo().getQueryParameters().forEach((key, values) -> {
            if (!values.isEmpty()) {
                params.put(key, values.get(0));
            }
        });
        return params;
    }

    /**
     * Aborts the request with a 401 Unauthorized response.
     */
    private void abortWithUnauthorized(ContainerRequestContext requestContext, String message) {
        requestContext.abortWith(Response
            .status(Response.Status.UNAUTHORIZED)
            .entity(new ErrorResponse("unauthorized", message))
            .header("WWW-Authenticate", "Bearer")
            .build());
    }

    /**
     * Aborts the request with a 403 Forbidden response.
     */
    private void abortWithForbidden(ContainerRequestContext requestContext, PolicyDecision decision) {
        requestContext.abortWith(Response
            .status(Response.Status.FORBIDDEN)
            .entity(new ErrorResponse(
                "forbidden",
                decision.reason() != null ? decision.reason() : "Access denied",
                decision.violations()
            ))
            .build());
    }

    /**
     * Aborts the request with a 500 Internal Server Error response.
     */
    private void abortWithError(ContainerRequestContext requestContext, String message) {
        requestContext.abortWith(Response
            .status(Response.Status.INTERNAL_SERVER_ERROR)
            .entity(new ErrorResponse("error", message))
            .build());
    }

    /**
     * Error response structure.
     */
    public record ErrorResponse(
        String code,
        String message,
        List<String> details
    ) {
        public ErrorResponse(String code, String message) {
            this(code, message, null);
        }
    }
}
