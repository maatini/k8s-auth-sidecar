package space.maatini.sidecar.application.service;
 
import io.quarkus.cache.CacheResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import space.maatini.sidecar.infrastructure.roles.RolesClient;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.domain.model.RolesResponse;
 
import java.util.HashSet;
import java.util.Set;
 
/**
 * Service for enriching the AuthContext with additional roles from an external
 * service.
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
                })
                .onFailure().transform(t -> new SecurityException("Roles enrichment failed for user " + userId, t));
    }
 
    @CacheResult(cacheName = "roles-cache")
    public Uni<RolesResponse> getEnrichedRoles(String userId) {
        LOG.debugf("Fetching external roles for user: %s", userId);
        return rolesClient.getUserRoles(userId);
    }
}
