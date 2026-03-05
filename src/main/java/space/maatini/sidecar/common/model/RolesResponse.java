package space.maatini.sidecar.common.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Set;

/**
 * Data model for the response from the external roles microservice.
 */
@RegisterForReflection
public record RolesResponse(
        String userId,
        Set<String> roles,
        Set<String> permissions) {
}
