package de.edeka.eit.sidecar.application.service;
 
import io.smallrye.mutiny.Uni;
import de.edeka.eit.sidecar.domain.model.AuthContext;
import de.edeka.eit.sidecar.domain.model.PolicyDecision;
 
import java.util.Map;
 
/**
 * Interface for policy evaluation, enabling dependency inversion.
 * Implementation will be in the opa-wasm module.
 */
public interface PolicyEngine {
    Uni<PolicyDecision> evaluate(AuthContext context, String method, String path, Map<String, String> headers, Map<String, String> queryParams);
}
