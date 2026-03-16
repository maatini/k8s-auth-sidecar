package de.edeka.eit.sidecar.domain.model;
 
import org.eclipse.microprofile.jwt.JsonWebToken;
import java.util.Map;

/**
 * REST-agnostic request model for sidecar processing.
 */
public record SidecarRequest(
        String method,
        String path,
        Map<String, String> headers,
        Map<String, String> queryParams,
        JsonWebToken jwt) {
}
