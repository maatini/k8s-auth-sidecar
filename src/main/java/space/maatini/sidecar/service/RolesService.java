package space.maatini.sidecar.service;

import io.quarkus.cache.CacheResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import space.maatini.sidecar.client.RolesServiceClient;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.model.RolesResponse;

import java.util.HashSet;
import java.util.Set;

/**
 * Service for fetching and enriching user roles and permissions from external services.
 */
@ApplicationScoped
public class RolesService {

    private static final Logger LOG = Logger.getLogger(RolesService.class);

    @Inject
    SidecarConfig config;

    @Inject
    @RestClient
    RolesServiceClient rolesServiceClient;

    /**
     * Enriches an AuthContext with roles and permissions from the external service.
     *
     * @param authContext The authentication context to enrich
     * @return A Uni containing the enriched AuthContext
     */
    public Uni<AuthContext> enrichWithRoles(AuthContext authContext) {
        if (!config.authz().rolesService().enabled()) {
            LOG.debug("Roles service is disabled, returning original context");
            return Uni.createFrom().item(authContext);
        }

        if (authContext == null || !authContext.isAuthenticated()) {
            LOG.debug("No authenticated user, skipping roles enrichment");
            return Uni.createFrom().item(authContext != null ? authContext : AuthContext.anonymous());
        }

        return fetchRoles(authContext.userId(), authContext.tenant())
            .onItem().transform(rolesResponse -> {
                if (rolesResponse == null) {
                    return authContext;
                }
                return enrichContext(authContext, rolesResponse);
            })
            .onFailure().recoverWithItem(error -> {
                LOG.warnf("Failed to fetch roles for user %s: %s", 
                    authContext.userId(), error.getMessage());
                // Return original context without additional roles on failure
                return authContext;
            });
    }

    /**
     * Fetches roles for a user from the external service with caching.
     *
     * @param userId The user ID
     * @param tenant The tenant context
     * @return A Uni containing the roles response
     */
    @CacheResult(cacheName = "roles-cache")
    public Uni<RolesResponse> fetchRoles(String userId, String tenant) {
        LOG.debugf("Fetching roles for user: %s, tenant: %s", userId, tenant);

        if (tenant != null && !tenant.isEmpty()) {
            return rolesServiceClient.getRolesForTenant(userId, tenant)
                .onFailure().recoverWithItem(error -> {
                    LOG.warnf("Failed to fetch roles with tenant: %s", error.getMessage());
                    return fallbackToDefault(userId);
                });
        }

        return rolesServiceClient.getRoles(userId)
            .onFailure().recoverWithItem(error -> {
                LOG.warnf("Failed to fetch roles: %s", error.getMessage());
                return fallbackToDefault(userId);
            });
    }

    /**
     * Fetches permissions for a user from the external service with caching.
     *
     * @param userId The user ID
     * @return A Uni containing the roles response with permissions
     */
    @CacheResult(cacheName = "permissions-cache")
    public Uni<RolesResponse> fetchPermissions(String userId) {
        LOG.debugf("Fetching permissions for user: %s", userId);

        return rolesServiceClient.getPermissions(userId)
            .onFailure().recoverWithItem(error -> {
                LOG.warnf("Failed to fetch permissions: %s", error.getMessage());
                return RolesResponse.empty(userId);
            });
    }

    /**
     * Enriches an AuthContext with roles from a RolesResponse.
     */
    private AuthContext enrichContext(AuthContext original, RolesResponse rolesResponse) {
        Set<String> combinedRoles = new HashSet<>(original.roles());
        Set<String> combinedPermissions = new HashSet<>(original.permissions());

        if (rolesResponse.roles() != null) {
            combinedRoles.addAll(rolesResponse.roles());
        }
        if (rolesResponse.permissions() != null) {
            combinedPermissions.addAll(rolesResponse.permissions());
        }

        return AuthContext.builder()
            .userId(original.userId())
            .email(original.email())
            .name(original.name())
            .preferredUsername(original.preferredUsername())
            .issuer(original.issuer())
            .audience(original.audience())
            .roles(combinedRoles)
            .permissions(combinedPermissions)
            .claims(original.claims())
            .issuedAt(original.issuedAt())
            .expiresAt(original.expiresAt())
            .tokenId(original.tokenId())
            .tenant(rolesResponse.tenant() != null ? rolesResponse.tenant() : original.tenant())
            .build();
    }

    /**
     * Provides a fallback response when the roles service is unavailable.
     */
    private RolesResponse fallbackToDefault(String userId) {
        LOG.warnf("Using fallback empty roles for user: %s", userId);
        return RolesResponse.empty(userId);
    }
}
