package space.maatini.sidecar.application.service;
 
import io.smallrye.mutiny.Uni;
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.domain.model.PolicyDecision;
 
import java.util.Map;
 
/**
 * Interface for policy evaluation, enabling dependency inversion.
 * Implementation will be in the opa-wasm module.
 */
public interface PolicyEngine {
    Uni<PolicyDecision> evaluate(AuthContext context, String method, String path, Map<String, String> headers, Map<String, String> queryParams);
}
