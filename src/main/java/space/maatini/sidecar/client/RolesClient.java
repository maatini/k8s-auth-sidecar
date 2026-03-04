package space.maatini.sidecar.client;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import space.maatini.sidecar.model.RolesResponse;

/**
 * REST Client for the external user roles enrichment service.
 */
@RegisterRestClient(configKey = "roles-api")
public interface RolesClient {

    @GET
    @Path("/{userId}/roles")
    Uni<RolesResponse> getUserRoles(@PathParam("userId") String userId);
}
