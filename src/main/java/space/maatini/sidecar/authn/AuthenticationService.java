package space.maatini.sidecar.authn;

import io.smallrye.mutiny.Uni;
import io.quarkus.cache.CacheResult;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import space.maatini.sidecar.common.config.SidecarConfig;
import space.maatini.sidecar.common.model.AuthContext;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for extracting authentication context from JWT tokens.
 * Handles claim extraction and normalization for OIDC tokens (optimized for
 * Keycloak).
 */
@ApplicationScoped
public class AuthenticationService {

    private static final Logger LOG = Logger.getLogger(AuthenticationService.class);

    // Standard JWT claims
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_NAME = "name";
    private static final String CLAIM_PREFERRED_USERNAME = "preferred_username";
    private static final String CLAIM_IAT = "iat";
    private static final String CLAIM_EXP = "exp";
    private static final String CLAIM_JTI = "jti";

    // Keycloak-specific claims
    private static final String CLAIM_REALM_ACCESS = "realm_access";
    private static final String CLAIM_RESOURCE_ACCESS = "resource_access";

    @Inject
    SidecarConfig config;

    /**
     * Extracts authentication context from a SecurityIdentity.
     */
    public Uni<AuthContext> extractAuthContext(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            LOG.debug("No security identity or anonymous user");
            return Uni.createFrom().item(AuthContext.anonymous());
        }

        try {
            JsonWebToken jwt = identity.getPrincipal() instanceof JsonWebToken
                    ? (JsonWebToken) identity.getPrincipal()
                    : null;

            if (jwt == null) {
                LOG.warn("Principal is not a JWT token");
                return Uni.createFrom().item(AuthContext.anonymous());
            }

            return getCachedAuthContext(jwt);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to extract auth context from security identity");
            return Uni.createFrom().item(AuthContext.anonymous());
        }
    }

    /**
     * Extracts authentication context from a JWT token with caching.
     */
    @CacheResult(cacheName = "jwt-cache")
    public Uni<AuthContext> getCachedAuthContext(JsonWebToken jwt) {
        LOG.debugf("Cache miss for JWT, extracting context");
        return Uni.createFrom().item(() -> extractFromJwt(jwt));
    }

    /**
     * Extracts authentication context from a JWT token.
     */
    public AuthContext extractFromJwt(JsonWebToken jwt) {
        if (jwt == null) {
            return AuthContext.anonymous();
        }

        String userId = jwt.getSubject();
        String email = extractClaim(jwt, CLAIM_EMAIL, String.class);
        String name = extractClaim(jwt, CLAIM_NAME, String.class);
        String preferredUsername = extractClaim(jwt, CLAIM_PREFERRED_USERNAME, String.class);

        Set<String> tokenRoles = extractRoles(jwt);
        List<String> audience = extractAudience(jwt);

        long issuedAt = extractLongClaim(jwt, CLAIM_IAT);
        long expiresAt = extractLongClaim(jwt, CLAIM_EXP);
        String tokenId = extractClaim(jwt, CLAIM_JTI, String.class);

        Map<String, Object> claims = extractAllClaims(jwt);

        AuthContext context = AuthContext.builder()
                .userId(userId)
                .email(email)
                .name(name)
                .preferredUsername(preferredUsername)
                .issuer(jwt.getIssuer())
                .audience(audience)
                .roles(tokenRoles)
                .permissions(Collections.emptySet())
                .claims(claims)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .tokenId(tokenId)
                .build();

        LOG.debugf("Extracted auth context for user: %s, roles: %s", userId, tokenRoles);

        return context;
    }

    /**
     * Extracts roles from the Keycloak token.
     */
    private Set<String> extractRoles(JsonWebToken jwt) {
        Set<String> roles = new HashSet<>();
        roles.addAll(extractKeycloakRealmRoles(jwt));
        roles.addAll(extractKeycloakResourceRoles(jwt));
        roles.addAll(extractGroupsClaim(jwt));
        return roles;
    }

    /**
     * Extracts roles from the 'groups' claim.
     */
    private Set<String> extractGroupsClaim(JsonWebToken jwt) {
        try {
            Set<String> groups = jwt.getGroups();
            if (groups != null) {
                return groups;
            }
        } catch (Exception e) {
            LOG.debugf("Failed to extract groups: %s", e.getMessage());
        }
        return Collections.emptySet();
    }

    /**
     * Extracts Keycloak realm roles from the 'realm_access' claim.
     */
    private Set<String> extractKeycloakRealmRoles(JsonWebToken jwt) {
        Object rolesObj;
        try {
            Map<String, Object> realmAccess = jwt.getClaim(CLAIM_REALM_ACCESS);
            if (realmAccess == null || !realmAccess.containsKey("roles")) {
                return Collections.emptySet();
            }
            rolesObj = realmAccess.get("roles");
        } catch (Exception e) {
            LOG.debugf("Failed to extract Keycloak realm roles: %s", e.getMessage());
            return Collections.emptySet();
        }

        if (rolesObj instanceof Collection) {
            return ((Collection<?>) rolesObj).stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .collect(Collectors.toSet());
        }
        return Collections.emptySet();
    }

    /**
     * Extracts Keycloak resource roles from the 'resource_access' claim.
     */
    @SuppressWarnings("unchecked")
    private Set<String> extractKeycloakResourceRoles(JsonWebToken jwt) {
        Set<String> roles = new HashSet<>();
        Map<String, Object> resourceAccess;
        try {
            resourceAccess = jwt.getClaim(CLAIM_RESOURCE_ACCESS);
        } catch (Exception e) {
            LOG.debugf("Failed to extract Keycloak resource roles: %s", e.getMessage());
            return roles;
        }

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
        return roles;
    }

    /**
     * Extracts the audience from the token.
     */
    private List<String> extractAudience(JsonWebToken jwt) {
        Set<String> audience;
        try {
            audience = jwt.getAudience();
        } catch (Exception e) {
            LOG.debugf("Failed to extract audience: %s", e.getMessage());
            return Collections.emptyList();
        }

        if (audience != null) {
            return new ArrayList<>(audience);
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
    @SuppressWarnings("unchecked")
    private <T> T extractClaim(JsonWebToken jwt, String claimName, Class<T> type) {
        try {
            return (T) jwt.getClaim(claimName);
        } catch (Exception e) {
            LOG.debugf("Failed to extract claim '%s': %s", claimName, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts a long claim from the token.
     */
    private long extractLongClaim(JsonWebToken jwt, String claimName) {
        Object value;
        try {
            value = jwt.getClaim(claimName);
        } catch (Exception e) {
            LOG.debugf("Failed to extract long claim '%s': %s", claimName, e.getMessage());
            return 0;
        }

        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0;
    }
}
