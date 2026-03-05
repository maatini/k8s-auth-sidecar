package space.maatini.sidecar.roles;

import io.quarkus.cache.CacheResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import space.maatini.sidecar.roles.client.RolesClient;
import space.maatini.sidecar.common.config.SidecarConfig;
import space.maatini.sidecar.common.model.AuthContext;
import space.maatini.sidecar.common.model.RolesResponse;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Service for enriching the AuthContext with additional roles from an external
 * service.
 * Implements caching and graceful fallbacks.
 */
@ApplicationScoped
public class RolesService {

    private static final Logger LOG = Logger.getLogger(RolesService.class);

    @Inject
    SidecarConfig config;

    @Inject
    @RestClient
    RolesClient rolesClient;

    /**
     * Enriches the authenticated context with external roles.
     * If enrichment is disabled or fails, returns the original context.
     */
    public Uni<AuthContext> enrich(AuthContext context) {
        if (!config.roles().enabled() || !context.isAuthenticated()) {
            return Uni.createFrom().item(context);
        }

        return getEnrichedRoles(context.userId())
                .onItem().transform(response -> {
                    Set<String> allRoles = new HashSet<>(context.roles());
                    Set<String> allPermissions = new HashSet<>(context.permissions());

                    if (response != null) {
                        if (response.roles() != null) {
                            allRoles.addAll(response.roles());
                        }
                        if (response.permissions() != null) {
                            allPermissions.addAll(response.permissions());
                        }
                    }

                    return context.withRolesAndPermissions(allRoles, allPermissions);
                })
                .onFailure().recoverWithItem(t -> {
                    LOG.errorf(t, "Failed to enrich roles for user: %s. Continuing with original roles.",
                            context.userId());
                    return context;
                });
    }

    /**
     * Fetches roles from the external microservice with caching.
     */
    @CacheResult(cacheName = "roles-cache")
    public Uni<RolesResponse> getEnrichedRoles(String userId) {
        LOG.debugf("Fetching external roles for user: %s", userId);
        return rolesClient.getUserRoles(userId);
    }
}
