package space.maatini.sidecar.application.service;

import io.smallrye.mutiny.Uni;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
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

    private final KeycloakRoleExtractor roleExtractor;
    private final AuthContextMapper authContextMapper;

    @Inject
    public AuthenticationService(KeycloakRoleExtractor roleExtractor, AuthContextMapper authContextMapper) {
        this.roleExtractor = roleExtractor;
        this.authContextMapper = authContextMapper;
    }

    public Uni<AuthContext> extractAuthContext(JsonWebToken jwt) {
        if (jwt == null) {
            LOG.debug("No JWT token provided");
            return Uni.createFrom().item(AuthContext.anonymous());
        }

        try {
            // Use Raw Token as stable cache key to avoid memory leak with proxy objects
            String cacheKey = jwt.getRawToken();
            if (cacheKey == null) {
                LOG.warn("JWT has no raw token, falling back to non-cached extraction");
                return Uni.createFrom().item(() -> extractFromJwt(jwt));
            }
            return getCachedAuthContext(cacheKey, jwt);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to extract auth context from JWT");
            return Uni.createFrom().item(AuthContext.anonymous());
        }
    }

    @CacheResult(cacheName = "jwt-cache")
    public Uni<AuthContext> getCachedAuthContext(@CacheKey String key, JsonWebToken jwt) {
        LOG.debugf("Cache miss for JWT %s, extracting context", key.substring(0, Math.min(key.length(), 10)) + "...");
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
