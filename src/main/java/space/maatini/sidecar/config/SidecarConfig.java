package space.maatini.sidecar.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;
import java.util.Map;

/**
 * Configuration mapping for the K8s-Auth-Sidecar.
 * All configuration is loaded from application.yaml under the 'sidecar' prefix.
 */
@ConfigMapping(prefix = "sidecar")
public interface SidecarConfig {

    ProxyConfig proxy();

    AuthConfig auth();

    AuthzConfig authz();

    OpaConfig opa();

    AuditConfig audit();

    RolesConfig roles();

    /**
     * Configuration for the external Roles Microservice.
     */
    interface RolesConfig {
        @WithDefault("false")
        boolean enabled();

        @WithDefault("http://localhost:8081")
        String url();

        @WithDefault("/api/v1/users/{userId}/roles")
        String path();

        @WithDefault("30s")
        java.time.Duration timeout();

        @WithDefault("1000")
        int cacheSize();

        @WithDefault("10m")
        java.time.Duration cacheTtl();
    }

    /**
     * Proxy configuration for the backend service.
     */
    interface ProxyConfig {
        TargetConfig target();

        TimeoutConfig timeout();

        @WithDefault("100")
        @WithName("pool-size")
        int poolSize();

        @WithDefault("X-Request-ID,X-Correlation-ID,X-Forwarded-For,X-Forwarded-Proto")
        List<String> propagateHeaders();

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
        @WithDefault("true")
        boolean enabled();

        List<String> publicPaths();

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
        @WithDefault("true")
        boolean enabled();
    }

    /**
     * OPA (Open Policy Agent) configuration – embedded WASM only.
     */
    interface OpaConfig {
        @WithDefault("true")
        boolean enabled();

        EmbeddedOpaConfig embedded();

        @WithName("default-package")
        @WithDefault("authz")
        String defaultPackage();

        @WithName("default-rule")
        @WithDefault("allow")
        String defaultRule();

        interface EmbeddedOpaConfig {
            @WithName("wasm-path")
            @WithDefault("classpath:policies/authz.wasm")
            String wasmPath();
        }
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
