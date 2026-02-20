package space.maatini.sidecar.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;
import java.util.Map;

/**
 * Configuration mapping for the RR-Sidecar.
 * All configuration is loaded from application.yaml under the 'sidecar' prefix.
 */
@ConfigMapping(prefix = "sidecar")
public interface SidecarConfig {

    /**
     * Proxy configuration for forwarding requests to the main container.
     */
    ProxyConfig proxy();

    /**
     * Authentication configuration.
     */
    AuthConfig auth();

    /**
     * Authorization configuration.
     */
    AuthzConfig authz();

    /**
     * OPA (Open Policy Agent) configuration.
     */
    OpaConfig opa();

    /**
     * Rate limiting configuration.
     */
    RateLimitConfig rateLimit();

    /**
     * Audit logging configuration.
     */
    AuditConfig audit();

    /**
     * Proxy configuration for the backend service.
     */
    interface ProxyConfig {
        /**
         * Target backend configuration.
         */
        TargetConfig target();

        /**
         * Timeout configuration.
         */
        TimeoutConfig timeout();

        /**
         * Headers to propagate from incoming request to backend.
         */
        @WithDefault("X-Request-ID,X-Correlation-ID,X-Forwarded-For,X-Forwarded-Proto")
        List<String> propagateHeaders();

        /**
         * Headers to add to backend request. Values can contain placeholders like
         * ${user.id}.
         */
        Map<String, String> addHeaders();

        interface TargetConfig {
            @WithDefault("localhost")
            String host();

            @WithDefault("8081")
            int port();

            @WithDefault("http")
            String scheme();
        }

        interface TimeoutConfig {
            @WithDefault("5000")
            int connect();

            @WithDefault("30000")
            int read();
        }
    }

    /**
     * Authentication configuration.
     */
    interface AuthConfig {
        /**
         * Enable/disable authentication.
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Paths that don't require authentication.
         */
        List<String> publicPaths();

        /**
         * Token extraction configuration.
         */
        TokenConfig token();

        interface TokenConfig {
            @WithDefault("Authorization")
            String headerName();

            @WithDefault("Bearer")
            String headerPrefix();

            @WithDefault("access_token")
            String cookieName();

            @WithDefault("token")
            String queryParam();
        }
    }

    /**
     * Authorization configuration.
     */
    interface AuthzConfig {
        /**
         * Enable/disable authorization.
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * Roles service client configuration.
         */
        RolesServiceConfig rolesService();

        /**
         * Permissions service client configuration.
         */
        PermissionsServiceConfig permissionsService();

        interface RolesServiceConfig {
            @WithDefault("true")
            boolean enabled();

            @WithDefault("/api/v1/users/{userId}/roles")
            String path();

            @WithDefault("true")
            boolean cacheEnabled();

            @WithDefault("300")
            int cacheTtl();
        }

        interface PermissionsServiceConfig {
            @WithDefault("true")
            boolean enabled();

            @WithDefault("/api/v1/users/{userId}/permissions")
            String path();

            @WithDefault("true")
            boolean cacheEnabled();

            @WithDefault("300")
            int cacheTtl();
        }
    }

    /**
     * OPA configuration.
     */
    interface OpaConfig {
        /**
         * Enable/disable OPA policy evaluation.
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * OPA mode: embedded or external.
         */
        @WithDefault("embedded")
        String mode();

        /**
         * External OPA server configuration.
         */
        ExternalOpaConfig external();

        /**
         * Embedded OPA configuration.
         */
        EmbeddedOpaConfig embedded();

        /**
         * Default policy package name.
         */
        @WithName("default-package")
        @WithDefault("authz")
        String defaultPackage();

        /**
         * Default rule name to evaluate.
         */
        @WithName("default-rule")
        @WithDefault("allow")
        String defaultRule();

        interface ExternalOpaConfig {
            @WithDefault("http://localhost:8181")
            String url();

            @WithName("decision-path")
            @WithDefault("/v1/data/authz/allow")
            String decisionPath();

            @WithDefault("5000")
            int timeout();
        }

        interface EmbeddedOpaConfig {
            @WithName("wasm-path")
            @WithDefault("classpath:policies/authz.wasm")
            String wasmPath();
        }
    }

    /**
     * Rate limiting configuration.
     */
    interface RateLimitConfig {
        @WithDefault("false")
        boolean enabled();

        @WithName("requests-per-second")
        @WithDefault("100")
        int requestsPerSecond();

        @WithName("burst-size")
        @WithDefault("200")
        int burstSize();
    }

    /**
     * Audit logging configuration.
     */
    interface AuditConfig {
        @WithDefault("true")
        boolean enabled();

        @WithName("log-request-body")
        @WithDefault("false")
        boolean logRequestBody();

        @WithName("log-response-body")
        @WithDefault("false")
        boolean logResponseBody();

        @WithName("sensitive-headers")
        @WithDefault("Authorization,Cookie,X-Api-Key")
        List<String> sensitiveHeaders();
    }
}
