package space.maatini.sidecar.domain.model;
 
import java.util.Map;
 
/**
 * REST-agnostic request model for sidecar processing.
 */
public record SidecarRequest(
        String method,
        String path,
        Map<String, String> headers,
        Map<String, String> queryParams) {
}
