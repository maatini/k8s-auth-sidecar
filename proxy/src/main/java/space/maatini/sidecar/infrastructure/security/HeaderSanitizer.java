package space.maatini.sidecar.infrastructure.security;

import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Sanitizes Envoy-forwarded headers to prevent header-spoofing attacks.
 * In ext_authz mode, path and method are read from X-Envoy-Original-Path
 * and X-Forwarded-Method. Without sanitization, an attacker can forge these
 * headers to bypass authorization (Privilege Escalation).
 *
 * Defense-in-Depth: The primary protection MUST happen at the Ingress Gateway
 * (Envoy/Nginx) by overwriting/stripping these headers for external clients.
 * This sanitizer provides a secondary safety net inside the sidecar.
 */
@ApplicationScoped
public class HeaderSanitizer {

    private static final Logger LOG = Logger.getLogger(HeaderSanitizer.class);

    /** Allowed HTTP methods – anything else falls back to the actual request method. */
    private static final Set<String> ALLOWED_METHODS = Set.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"
    );

    /** Headers that Envoy sets internally – must NOT be forwarded to OPA policy input. */
    private static final Set<String> ENVOY_INTERNAL_HEADERS = Set.of(
            "x-envoy-original-path",
            "x-envoy-original-method",
            "x-envoy-internal",
            "x-envoy-decorator-operation",
            "x-envoy-expected-rq-timeout-ms",
            "x-forwarded-method",
            "x-forwarded-uri"
    );

    /**
     * Extracts and normalizes the original request path.
     * Uses X-Envoy-Original-Path if present, otherwise falls back to request.path().
     * Applies path normalization to block traversal attacks.
     */
    public String extractPath(HttpServerRequest request) {
        String header = request.getHeader("X-Envoy-Original-Path");
        if (header != null && !header.isBlank()) {
            String normalized = normalizePath(header);
            if (!header.equals(normalized)) {
                LOG.warnf("Path header was normalized: '%s' -> '%s'", header, normalized);
            }
            return normalized;
        }
        return request.path();
    }

    /**
     * Extracts and validates the original HTTP method.
     * Uses X-Forwarded-Method if present and valid, otherwise falls back to request.method().
     */
    public String extractMethod(HttpServerRequest request) {
        String header = request.getHeader("X-Forwarded-Method");
        if (header != null && !header.isBlank()) {
            String upper = header.trim().toUpperCase();
            if (ALLOWED_METHODS.contains(upper)) {
                return upper;
            }
            LOG.warnf("Rejected invalid HTTP method from header: '%s', using actual method", header);
        }
        return request.method().name();
    }

    /**
     * Checks if a given header name (lowercased) is an internal Envoy header
     * that should NOT be forwarded to the policy engine input.
     */
    public boolean isEnvoyInternalHeader(String headerNameLower) {
        return ENVOY_INTERNAL_HEADERS.contains(headerNameLower);
    }

    /**
     * Normalizes a URI path to prevent traversal attacks.
     * <ul>
     *   <li>URL-decodes percent-encoded sequences</li>
     *   <li>Collapses double slashes (//)</li>
     *   <li>Removes path traversal segments (..)</li>
     *   <li>Ensures the path starts with /</li>
     * </ul>
     */
    static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }

        // Strip query string if present (only the path segment matters)
        int queryIdx = path.indexOf('?');
        String pathOnly = queryIdx >= 0 ? path.substring(0, queryIdx) : path;

        // Decode percent-encoded characters (handles %2e%2e, %2F, etc.)
        String decoded = urlDecode(pathOnly);

        // Collapse double slashes
        while (decoded.contains("//")) {
            decoded = decoded.replace("//", "/");
        }

        // Remove path traversal segments
        String[] segments = decoded.split("/");
        StringBuilder result = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                // Remove last segment (go up one level) – but never above root
                int lastSlash = result.lastIndexOf("/");
                if (lastSlash >= 0) {
                    result.setLength(lastSlash);
                }
                continue;
            }
            result.append('/').append(segment);
        }

        if (result.isEmpty()) {
            return "/";
        }

        // Re-append query string if it was present
        if (queryIdx >= 0) {
            result.append(path.substring(queryIdx));
        }

        return result.toString();
    }

    private static String urlDecode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Malformed percent-encoding – return as-is
            LOG.warnf("Failed to URL-decode path segment: '%s'", value);
            return value;
        }
    }
}
