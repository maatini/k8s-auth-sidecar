package space.maatini.sidecar.infrastructure.security;

import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URISyntaxException;
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
     * Uses {@link URI#normalize()} (RFC 3986) instead of custom logic
     * to avoid bypass risks from double encoding or Unicode evasion.
     * <ul>
     *   <li>URL-decodes percent-encoded sequences before normalization</li>
     *   <li>Delegates to java.net.URI for .., ., // handling</li>
     *   <li>Blocks traversal above root</li>
     *   <li>Ensures the path starts with /</li>
     * </ul>
     */
    static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }

        // Strip query string – normalize only the path segment
        int queryIdx = path.indexOf('?');
        String pathOnly = queryIdx >= 0 ? path.substring(0, queryIdx) : path;

        // Decode percent-encoded characters before URI normalization
        // (catches %2e%2e, %2F, etc.)
        String decoded = urlDecode(pathOnly);

        // Collapse double slashes before URI parsing
        // (URI treats // as authority separator per RFC 3986)
        while (decoded.contains("//")) {
            decoded = decoded.replace("//", "/");
        }

        // Ensure path starts with /
        if (!decoded.startsWith("/")) {
            decoded = "/" + decoded;
        }

        // Use 5-arg URI constructor to prevent internal percent-decode
        // This keeps %2e as literal %2e instead of resolving to .
        try {
            String normalized = new URI(null, null, decoded, null, null)
                    .normalize().getPath();
            if (normalized == null || normalized.isEmpty()) {
                normalized = "/";
            }
            // Block path-traversal above root
            // URI.normalize() may produce leading /../ for excessive ..
            while (normalized.startsWith("/..")) {
                if (normalized.equals("/..")) {
                    normalized = "/";
                    break;
                }
                if (normalized.startsWith("/../")) {
                    normalized = normalized.substring(3);
                } else {
                    break;
                }
            }
            if (normalized.isEmpty()) {
                normalized = "/";
            }
            // Re-append query string if present
            if (queryIdx >= 0) {
                return normalized + path.substring(queryIdx);
            }
            return normalized;
        } catch (URISyntaxException e) {
            LOG.warnf("Failed to parse path as URI: '%s', returning /", path);
            return "/";
        }
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
