package de.edeka.eit.sidecar.application.service;
 
import io.quarkus.cache.CacheResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import de.edeka.eit.sidecar.infrastructure.roles.RolesClient;
import de.edeka.eit.sidecar.infrastructure.config.SidecarConfig;
import de.edeka.eit.sidecar.domain.model.AuthContext;
import de.edeka.eit.sidecar.domain.model.RolesResponse;

import java.util.HashSet;
import java.util.Set;

/**
 * Service for enriching the AuthContext with additional roles from an external
 * service. Uses SmallRye Fault Tolerance for resilience (Timeout, CircuitBreaker,
 * Fallback) to prevent thread starvation when the roles service is slow or down.
 */
@ApplicationScoped
public class RolesService {
 
    private static final Logger LOG = Logger.getLogger(RolesService.class);
 
    @Inject
    SidecarConfig config;
 
    @Inject
    @RestClient
    RolesClient rolesClient;
 
    public Uni<AuthContext> enrich(AuthContext context) {
        if (!config.roles().enabled() || !context.isAuthenticated()) {
            return Uni.createFrom().item(context);
        }

        String userId = context.userId();
        return getEnrichedRoles(userId)
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
                });
    }
 
    @CacheResult(cacheName = "roles-cache")
    @Timeout(200)
    @CircuitBreaker(requestVolumeThreshold = 5, failureRatio = 0.5, delay = 10000)
    @Fallback(fallbackMethod = "fallbackRoles")
    public Uni<RolesResponse> getEnrichedRoles(String userId) {
        LOG.debugf("Fetching external roles for user: %s", userId);
        return rolesClient.getUserRoles(userId);
    }

    /**
     * Fallback: returns empty roles when the Roles-Service is unavailable or too slow.
     * The AuthContext will retain its original JWT-based roles (Fail-Open strategy).
     */
    Uni<RolesResponse> fallbackRoles(String userId) {
        LOG.warnf("Roles enrichment fallback for user %s — using JWT-only roles", userId);
        return Uni.createFrom().item(new RolesResponse(userId, Set.of(), Set.of()));
    }
}
