package space.maatini.sidecar.service;

import io.quarkus.oidc.OidcTenantConfig;
import io.quarkus.oidc.TenantResolver;
import io.quarkus.oidc.runtime.OidcUtils;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Multi-tenant resolver for OIDC that supports both Keycloak and Microsoft
 * Entra ID.
 * The tenant is determined from the JWT token's issuer claim or from request
 * headers.
 */
@ApplicationScoped
public class MultiTenantResolver implements TenantResolver {

    private static final Logger LOG = Logger.getLogger(MultiTenantResolver.class);

    private static final String TENANT_HEADER = "X-Tenant-ID";
    private static final String KEYCLOAK_TENANT = "default";
    private static final String ENTRA_TENANT = "entra";

    @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "false")
    boolean tenantEnabled;

    @ConfigProperty(name = "sidecar.auth.enabled", defaultValue = "true")
    boolean authEnabled;

    /**
     * Resolves the OIDC tenant based on the request context.
     * 
     * @param context The routing context containing the request
     * @return The tenant identifier to use for token validation
     */
    @Override
    public String resolve(RoutingContext context) {
        if (!tenantEnabled || !authEnabled) {
            return KEYCLOAK_TENANT;
        }

        // First, check for explicit tenant header
        String tenantHeader = context.request().getHeader(TENANT_HEADER);
        if (tenantHeader != null && !tenantHeader.isEmpty()) {
            LOG.debugf("Tenant resolved from header: %s", tenantHeader);
            return tenantHeader.toLowerCase();
        }

        // Try to determine tenant from the token issuer
        String authHeader = context.request().getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            Optional<String> issuer = extractIssuerFromToken(token);

            if (issuer.isPresent()) {
                String iss = issuer.get();
                if (isEntraIssuer(iss)) {
                    LOG.debugf("Tenant resolved from issuer (Entra ID): %s", iss);
                    return ENTRA_TENANT;
                } else if (isKeycloakIssuer(iss)) {
                    LOG.debugf("Tenant resolved from issuer (Keycloak): %s", iss);
                    return KEYCLOAK_TENANT;
                }
            }
        }

        // Default to Keycloak tenant
        LOG.debug("Using default tenant: " + KEYCLOAK_TENANT);
        return KEYCLOAK_TENANT;
    }

    /**
     * Extracts the issuer claim from a JWT token without full validation.
     * This is used only for tenant routing, actual validation happens later.
     */
    private Optional<String> extractIssuerFromToken(String token) {
        try {
            // Parse the payload without validation to get the issuer
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }

            String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));

            // Use Jackson for robust JSON parsing instead of fragile string manipulation
            com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(payload);
            com.fasterxml.jackson.databind.JsonNode issNode = node.get("iss");

            if (issNode != null && issNode.isTextual()) {
                return Optional.of(issNode.asText());
            }
        } catch (Exception e) {
            LOG.debugf("Failed to extract issuer from token: %s", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Checks if the issuer is a Microsoft Entra ID (Azure AD) issuer.
     */
    private boolean isEntraIssuer(String issuer) {
        return issuer != null && (issuer.contains("login.microsoftonline.com") ||
                issuer.contains("sts.windows.net") ||
                issuer.contains("login.microsoft.com"));
    }

    /**
     * Checks if the issuer is a Keycloak issuer.
     */
    private boolean isKeycloakIssuer(String issuer) {
        return issuer != null && (issuer.contains("/realms/") ||
                issuer.contains("keycloak"));
    }
}
