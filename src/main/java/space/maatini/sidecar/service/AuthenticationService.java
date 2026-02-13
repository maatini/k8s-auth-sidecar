package space.maatini.sidecar.service;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.Claims;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for extracting authentication context from JWT tokens.
 * Handles claim extraction and normalization for both Keycloak and Entra ID
 * tokens.
 */
@ApplicationScoped
public class AuthenticationService {

    private static final Logger LOG = Logger.getLogger(AuthenticationService.class);

    // Standard JWT claims
    private static final String CLAIM_SUB = "sub";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_NAME = "name";
    private static final String CLAIM_PREFERRED_USERNAME = "preferred_username";
    private static final String CLAIM_ISS = "iss";
    private static final String CLAIM_AUD = "aud";
    private static final String CLAIM_IAT = "iat";
    private static final String CLAIM_EXP = "exp";
    private static final String CLAIM_JTI = "jti";

    // Keycloak-specific claims
    private static final String CLAIM_REALM_ACCESS = "realm_access";
    private static final String CLAIM_RESOURCE_ACCESS = "resource_access";

    // Entra ID-specific claims
    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_GROUPS = "groups";
    private static final String CLAIM_OID = "oid";
    private static final String CLAIM_TID = "tid";
    private static final String CLAIM_UPN = "upn";

    @Inject
    SidecarConfig config;

    /**
     * Extracts authentication context from a SecurityIdentity.
     *
     * @param identity The security identity from Quarkus OIDC
     * @return The extracted authentication context
     */
    public AuthContext extractAuthContext(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            LOG.debug("No security identity or anonymous user");
            return AuthContext.anonymous();
        }

        try {
            JsonWebToken jwt = identity.getPrincipal() instanceof JsonWebToken
                    ? (JsonWebToken) identity.getPrincipal()
                    : null;

            if (jwt == null) {
                LOG.warn("Principal is not a JWT token");
                return AuthContext.anonymous();
            }

            return extractFromJwt(jwt);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to extract auth context from security identity");
            return AuthContext.anonymous();
        }
    }

    /**
     * Extracts authentication context from a JWT token.
     *
     * @param jwt The parsed JWT token
     * @return The extracted authentication context
     */
    public AuthContext extractFromJwt(JsonWebToken jwt) {
        if (jwt == null) {
            return AuthContext.anonymous();
        }

        String issuer = jwt.getIssuer();
        boolean isEntraToken = isEntraIssuer(issuer);

        String userId = extractUserId(jwt, isEntraToken);
        String email = extractClaim(jwt, CLAIM_EMAIL, String.class);
        String name = extractClaim(jwt, CLAIM_NAME, String.class);
        String preferredUsername = extractPreferredUsername(jwt, isEntraToken);

        Set<String> tokenRoles = extractRoles(jwt, isEntraToken);
        List<String> audience = extractAudience(jwt);

        long issuedAt = extractLongClaim(jwt, CLAIM_IAT);
        long expiresAt = extractLongClaim(jwt, CLAIM_EXP);
        String tokenId = extractClaim(jwt, CLAIM_JTI, String.class);
        String tenant = isEntraToken
                ? extractClaim(jwt, CLAIM_TID, String.class)
                : extractTenantFromIssuer(issuer);

        Map<String, Object> claims = extractAllClaims(jwt);

        AuthContext context = AuthContext.builder()
                .userId(userId)
                .email(email)
                .name(name)
                .preferredUsername(preferredUsername)
                .issuer(issuer)
                .audience(audience)
                .roles(tokenRoles)
                .permissions(Collections.emptySet()) // Filled later by authorization service
                .claims(claims)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .tokenId(tokenId)
                .tenant(tenant)
                .build();

        LOG.debugf("Extracted auth context for user: %s, roles: %s, tenant: %s",
                userId, tokenRoles, tenant);

        return context;
    }

    /**
     * Extracts the user ID from the token.
     * For Entra ID, uses 'oid' claim; for Keycloak, uses 'sub' claim.
     */
    private String extractUserId(JsonWebToken jwt, boolean isEntraToken) {
        if (isEntraToken) {
            // Entra ID uses 'oid' as the immutable user identifier
            String oid = extractClaim(jwt, CLAIM_OID, String.class);
            if (oid != null) {
                return oid;
            }
        }
        // Fall back to 'sub' claim
        return jwt.getSubject();
    }

    /**
     * Extracts the preferred username from the token.
     */
    private String extractPreferredUsername(JsonWebToken jwt, boolean isEntraToken) {
        String username = extractClaim(jwt, CLAIM_PREFERRED_USERNAME, String.class);
        if (username == null && isEntraToken) {
            // Entra ID might use 'upn' (User Principal Name) instead
            username = extractClaim(jwt, CLAIM_UPN, String.class);
        }
        return username;
    }

    /**
     * Extracts roles from the token based on the identity provider.
     */
    private Set<String> extractRoles(JsonWebToken jwt, boolean isEntraToken) {
        Set<String> roles = new HashSet<>();

        if (isEntraToken) {
            // Entra ID: roles are in 'roles' claim
            roles.addAll(extractClaimAsStringSet(jwt, CLAIM_ROLES));
            // Also check 'groups' claim
            roles.addAll(extractClaimAsStringSet(jwt, CLAIM_GROUPS));
        } else {
            // Keycloak: roles are in 'realm_access.roles' and
            // 'resource_access.{client}.roles'
            roles.addAll(extractKeycloakRealmRoles(jwt));
            roles.addAll(extractKeycloakResourceRoles(jwt));
        }

        return roles;
    }

    /**
     * Extracts Keycloak realm roles from the 'realm_access' claim.
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractKeycloakRealmRoles(JsonWebToken jwt) {
        try {
            Map<String, Object> realmAccess = jwt.getClaim(CLAIM_REALM_ACCESS);
            if (realmAccess != null && realmAccess.containsKey("roles")) {
                Object rolesObj = realmAccess.get("roles");
                if (rolesObj instanceof Collection) {
                    return ((Collection<?>) rolesObj).stream()
                            .filter(String.class::isInstance)
                            .map(String.class::cast)
                            .collect(Collectors.toSet());
                }
            }
        } catch (Exception e) {
            LOG.debugf("Failed to extract Keycloak realm roles: %s", e.getMessage());
        }
        return Collections.emptySet();
    }

    /**
     * Extracts Keycloak resource roles from the 'resource_access' claim.
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractKeycloakResourceRoles(JsonWebToken jwt) {
        Set<String> roles = new HashSet<>();
        try {
            Map<String, Object> resourceAccess = jwt.getClaim(CLAIM_RESOURCE_ACCESS);
            if (resourceAccess != null) {
                for (Map.Entry<String, Object> entry : resourceAccess.entrySet()) {
                    String clientId = entry.getKey();
                    if (entry.getValue() instanceof Map) {
                        Map<String, Object> clientAccess = (Map<String, Object>) entry.getValue();
                        if (clientAccess.containsKey("roles")) {
                            Object rolesObj = clientAccess.get("roles");
                            if (rolesObj instanceof Collection) {
                                ((Collection<?>) rolesObj).stream()
                                        .filter(String.class::isInstance)
                                        .map(String.class::cast)
                                        .map(role -> clientId + ":" + role)
                                        .forEach(roles::add);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugf("Failed to extract Keycloak resource roles: %s", e.getMessage());
        }
        return roles;
    }

    /**
     * Extracts the audience from the token.
     */
    @SuppressWarnings("unchecked")
    private List<String> extractAudience(JsonWebToken jwt) {
        try {
            Set<String> audience = jwt.getAudience();
            if (audience != null) {
                return new ArrayList<>(audience);
            }
        } catch (Exception e) {
            LOG.debugf("Failed to extract audience: %s", e.getMessage());
        }
        return Collections.emptyList();
    }

    /**
     * Extracts all claims from the token as a map.
     */
    private Map<String, Object> extractAllClaims(JsonWebToken jwt) {
        Map<String, Object> claims = new HashMap<>();
        try {
            for (String claimName : jwt.getClaimNames()) {
                Object value = jwt.getClaim(claimName);
                if (value != null) {
                    claims.put(claimName, value);
                }
            }
        } catch (Exception e) {
            LOG.debugf("Failed to extract all claims: %s", e.getMessage());
        }
        return claims;
    }

    /**
     * Extracts a typed claim from the token.
     */
    private <T> T extractClaim(JsonWebToken jwt, String claimName, Class<T> type) {
        try {
            return jwt.getClaim(claimName);
        } catch (Exception e) {
            LOG.debugf("Failed to extract claim '%s': %s", claimName, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts a long claim from the token.
     */
    private long extractLongClaim(JsonWebToken jwt, String claimName) {
        try {
            Object value = jwt.getClaim(claimName);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (Exception e) {
            LOG.debugf("Failed to extract long claim '%s': %s", claimName, e.getMessage());
        }
        return 0;
    }

    /**
     * Extracts a claim as a set of strings.
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractClaimAsStringSet(JsonWebToken jwt, String claimName) {
        try {
            Object value = jwt.getClaim(claimName);
            if (value instanceof Collection) {
                return ((Collection<?>) value).stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .collect(Collectors.toSet());
            } else if (value instanceof String) {
                return Set.of((String) value);
            }
        } catch (Exception e) {
            LOG.debugf("Failed to extract string set claim '%s': %s", claimName, e.getMessage());
        }
        return Collections.emptySet();
    }

    /**
     * Extracts tenant from Keycloak issuer URL.
     * Assumes format: https://keycloak.example.com/realms/{realm}
     */
    private String extractTenantFromIssuer(String issuer) {
        if (issuer != null && issuer.contains("/realms/")) {
            int idx = issuer.lastIndexOf("/realms/");
            return issuer.substring(idx + 8);
        }
        return null;
    }

    /**
     * Checks if the issuer is a Microsoft Entra ID issuer.
     */
    private boolean isEntraIssuer(String issuer) {
        return issuer != null && (issuer.contains("login.microsoftonline.com") ||
                issuer.contains("sts.windows.net") ||
                issuer.contains("login.microsoft.com"));
    }
}
