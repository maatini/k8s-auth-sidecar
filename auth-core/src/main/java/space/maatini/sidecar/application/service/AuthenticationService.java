package space.maatini.sidecar.application.service;

import io.smallrye.mutiny.Uni;
import io.quarkus.cache.CacheResult;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import space.maatini.sidecar.domain.model.AuthContext;

import java.util.Set;

/**
 * Service for extracting authentication context from JWT tokens.
 * Refactored to use modular components via Dependency Inversion.
 */
@ApplicationScoped
public class AuthenticationService {

    private static final Logger LOG = Logger.getLogger(AuthenticationService.class);

    @Inject
    KeycloakRoleExtractor roleExtractor;

    @Inject
    AuthContextMapper authContextMapper;

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

    @CacheResult(cacheName = "jwt-cache")
    public Uni<AuthContext> getCachedAuthContext(JsonWebToken jwt) {
        LOG.debugf("Cache miss for JWT, extracting context");
        return Uni.createFrom().item(() -> extractFromJwt(jwt));
    }

    public AuthContext extractFromJwt(JsonWebToken jwt) {
        if (jwt == null) {
            return AuthContext.anonymous();
        }

        Set<String> tokenRoles = roleExtractor.extractRoles(jwt);
        return authContextMapper.mapToAuthContext(jwt, tokenRoles);
    }
}
