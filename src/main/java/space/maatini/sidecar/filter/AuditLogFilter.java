package space.maatini.sidecar.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filter for audit logging of all requests.
 * Logs request and response details in a structured format suitable for security auditing.
 */
@Provider
@Priority(Priorities.USER - 100) // Run early to capture timing, but after auth
@ApplicationScoped
public class AuditLogFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final Logger AUDIT_LOG = Logger.getLogger("audit");
    private static final Logger LOG = Logger.getLogger(AuditLogFilter.class);

    private static final String START_TIME_PROPERTY = "audit.startTime";
    private static final String REQUEST_ID_PROPERTY = "audit.requestId";
    private static final String AUTH_CONTEXT_PROPERTY = "auth.context";

    @Inject
    SidecarConfig config;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    MeterRegistry meterRegistry;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        if (!config.audit().enabled()) {
            return;
        }

        // Store start time for duration calculation
        requestContext.setProperty(START_TIME_PROPERTY, System.currentTimeMillis());

        // Generate or extract request ID
        String requestId = requestContext.getHeaderString("X-Request-ID");
        if (requestId == null) {
            requestId = java.util.UUID.randomUUID().toString();
        }
        requestContext.setProperty(REQUEST_ID_PROPERTY, requestId);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) throws IOException {
        if (!config.audit().enabled()) {
            return;
        }

        try {
            Long startTime = (Long) requestContext.getProperty(START_TIME_PROPERTY);
            long duration = startTime != null ? System.currentTimeMillis() - startTime : 0;

            String requestId = (String) requestContext.getProperty(REQUEST_ID_PROPERTY);
            AuthContext authContext = (AuthContext) requestContext.getProperty(AUTH_CONTEXT_PROPERTY);

            AuditLogEntry entry = buildAuditEntry(
                requestContext,
                responseContext,
                requestId,
                authContext,
                duration
            );

            // Log as JSON for structured logging
            String logJson = objectMapper.writeValueAsString(entry);
            AUDIT_LOG.info(logJson);

        } catch (Exception e) {
            LOG.warnf("Failed to write audit log: %s", e.getMessage());
        }
    }

    /**
     * Builds an audit log entry from request/response context.
     */
    private AuditLogEntry buildAuditEntry(
            ContainerRequestContext request,
            ContainerResponseContext response,
            String requestId,
            AuthContext authContext,
            long duration) {

        // Extract user info
        String userId = authContext != null ? authContext.userId() : "anonymous";
        String userEmail = authContext != null ? authContext.email() : null;
        String tenant = authContext != null ? authContext.tenant() : null;

        // Extract request info
        String method = request.getMethod();
        String path = request.getUriInfo().getPath();
        String queryString = request.getUriInfo().getRequestUri().getQuery();
        String remoteAddress = extractRemoteAddress(request);
        String userAgent = request.getHeaderString("User-Agent");

        // Filter sensitive headers
        Map<String, String> requestHeaders = filterSensitiveHeaders(
            request.getHeaders().entrySet().stream()
                .collect(Collectors.toMap(
                    Map.Entry::getKey,
                    e -> e.getValue().isEmpty() ? "" : e.getValue().get(0)
                ))
        );

        // Response info
        int statusCode = response.getStatus();
        String statusFamily = getStatusFamily(statusCode);

        return new AuditLogEntry(
            Instant.now().toString(),
            requestId,
            "request",
            new UserInfo(userId, userEmail, tenant),
            new RequestInfo(method, path, queryString, remoteAddress, userAgent, requestHeaders),
            new ResponseInfo(statusCode, statusFamily, duration),
            determineOutcome(statusCode)
        );
    }

    /**
     * Extracts the remote address from the request.
     */
    private String extractRemoteAddress(ContainerRequestContext request) {
        // Check X-Forwarded-For header first (for proxied requests)
        String forwardedFor = request.getHeaderString("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            // Take the first IP in the chain
            return forwardedFor.split(",")[0].trim();
        }

        // Check X-Real-IP header
        String realIp = request.getHeaderString("X-Real-IP");
        if (realIp != null && !realIp.isEmpty()) {
            return realIp;
        }

        // Fallback - this would need access to the servlet request
        return "unknown";
    }

    /**
     * Filters out sensitive headers from logging.
     */
    private Map<String, String> filterSensitiveHeaders(Map<String, String> headers) {
        Set<String> sensitiveHeaders = Set.copyOf(config.audit().sensitiveHeaders());
        Map<String, String> filtered = new HashMap<>();
        
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String headerName = entry.getKey();
            if (sensitiveHeaders.stream().anyMatch(h -> h.equalsIgnoreCase(headerName))) {
                filtered.put(headerName, "[REDACTED]");
            } else {
                filtered.put(headerName, entry.getValue());
            }
        }
        
        return filtered;
    }

    /**
     * Returns the status family (1xx, 2xx, 3xx, 4xx, 5xx).
     */
    private String getStatusFamily(int statusCode) {
        return switch (statusCode / 100) {
            case 1 -> "INFORMATIONAL";
            case 2 -> "SUCCESS";
            case 3 -> "REDIRECTION";
            case 4 -> "CLIENT_ERROR";
            case 5 -> "SERVER_ERROR";
            default -> "UNKNOWN";
        };
    }

    /**
     * Determines the outcome based on status code.
     */
    private String determineOutcome(int statusCode) {
        return switch (statusCode) {
            case 401 -> "AUTHENTICATION_FAILED";
            case 403 -> "AUTHORIZATION_DENIED";
            case 404 -> "NOT_FOUND";
            case 429 -> "RATE_LIMITED";
            default -> {
                if (statusCode >= 200 && statusCode < 300) {
                    yield "SUCCESS";
                } else if (statusCode >= 400 && statusCode < 500) {
                    yield "CLIENT_ERROR";
                } else if (statusCode >= 500) {
                    yield "SERVER_ERROR";
                }
                yield "UNKNOWN";
            }
        };
    }

    /**
     * Audit log entry structure.
     */
    public record AuditLogEntry(
        String timestamp,
        String requestId,
        String eventType,
        UserInfo user,
        RequestInfo request,
        ResponseInfo response,
        String outcome
    ) {}

    public record UserInfo(
        String id,
        String email,
        String tenant
    ) {}

    public record RequestInfo(
        String method,
        String path,
        String queryString,
        String remoteAddress,
        String userAgent,
        Map<String, String> headers
    ) {}

    public record ResponseInfo(
        int statusCode,
        String statusFamily,
        long durationMs
    ) {}
}
