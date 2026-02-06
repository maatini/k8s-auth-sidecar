package space.maatini.sidecar.client;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import space.maatini.sidecar.model.RolesResponse;

/**
 * REST client for the external roles/permissions microservice.
 * This client fetches user roles and permissions for authorization decisions.
 */
@RegisterRestClient(configKey = "space.maatini.sidecar.client.RolesServiceClient")
@Path("/api/v1")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface RolesServiceClient {

    /**
     * Fetches roles for a specific user.
     *
     * @param userId The user ID to fetch roles for
     * @return The roles response containing user roles and permissions
     */
    @GET
    @Path("/users/{userId}/roles")
    Uni<RolesResponse> getRoles(@PathParam("userId") String userId);

    /**
     * Fetches roles for a specific user within a tenant context.
     *
     * @param userId The user ID to fetch roles for
     * @param tenantId The tenant to scope the query to
     * @return The roles response containing user roles and permissions
     */
    @GET
    @Path("/users/{userId}/roles")
    Uni<RolesResponse> getRolesForTenant(
        @PathParam("userId") String userId,
        @HeaderParam("X-Tenant-ID") String tenantId
    );

    /**
     * Fetches permissions for a specific user.
     *
     * @param userId The user ID to fetch permissions for
     * @return The roles response containing user permissions
     */
    @GET
    @Path("/users/{userId}/permissions")
    Uni<RolesResponse> getPermissions(@PathParam("userId") String userId);

    /**
     * Fetches both roles and permissions for a specific user in a single call.
     *
     * @param userId The user ID to fetch roles and permissions for
     * @return The roles response containing user roles and permissions
     */
    @GET
    @Path("/users/{userId}/authorization")
    Uni<RolesResponse> getAuthorization(@PathParam("userId") String userId);
}
