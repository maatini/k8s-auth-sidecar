package de.edeka.eit.sidecar.domain.model;
 
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Set;
 
/**
 * Data model for the response from the external roles microservice.
 */
@RegisterForReflection(registerFullHierarchy = true)
public record RolesResponse(
        String userId,
        Set<String> roles,
        Set<String> permissions) {
}
