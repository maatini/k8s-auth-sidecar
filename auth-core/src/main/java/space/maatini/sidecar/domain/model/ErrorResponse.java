package space.maatini.sidecar.domain.model;
 
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
 
/**
 * DTO for error responses. Used by ProcessingResult to build responses.
 */
@RegisterForReflection(registerFullHierarchy = true)
public record ErrorResponse(String error, String message, List<String> details) {
    public ErrorResponse(String error, String message) {
        this(error, message, List.of());
    }
}
