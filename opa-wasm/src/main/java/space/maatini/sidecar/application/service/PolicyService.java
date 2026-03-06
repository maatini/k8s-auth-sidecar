package space.maatini.sidecar.application.service;
 
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.cache.CacheResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.domain.model.PolicyDecision;
import space.maatini.sidecar.domain.model.PolicyInput;
import space.maatini.sidecar.infrastructure.policy.WasmPolicyEngine;
 
import java.util.*;
 
/**
 * Service for evaluating authorization policies using embedded OPA WASM.
 * Implements the PolicyEngine interface from auth-core.
 */
@ApplicationScoped
public class PolicyService implements PolicyEngine {
 
    private static final Logger LOG = Logger.getLogger(PolicyService.class);
 
    @Inject
    SidecarConfig config;
 
    @Inject
    ObjectMapper objectMapper;
 
    @Inject
    WasmPolicyEngine wasmEngine;
 
    @Override
    public Uni<PolicyDecision> evaluate(
            AuthContext authContext,
            String method,
            String path,
            Map<String, String> headers,
            Map<String, String> queryParams) {
 
        if (!config.opa().enabled()) {
            LOG.debug("OPA is disabled, allowing request by default");
            return Uni.createFrom().item(PolicyDecision.allow());
        }
 
        PolicyInput input = PolicyInput.from(authContext, method, path, headers, queryParams);
        return evaluatePolicy(input);
    }
 
    @CacheResult(cacheName = "policy-decision-cache")
    public Uni<PolicyDecision> evaluatePolicy(PolicyInput input) {
        return wasmEngine.evaluateEmbeddedWasm(input)
                .onFailure().recoverWithItem(error -> {
                    LOG.warnf("WASM evaluation failed on path %s: %s",
                            input.request().path(), error.getMessage());
                    return PolicyDecision.deny(
                            "Policy evaluation failed. Access denied for security.");
                });
    }
 
    public static PolicyDecision parsePolicyResult(JsonNode result) {
        if (result == null || result.isNull()) {
            return PolicyDecision.deny("No result from OPA");
        }
 
        if (result.isBoolean()) {
            return result.asBoolean()
                    ? PolicyDecision.allow()
                    : PolicyDecision.deny("Access denied by policy");
        }
 
        if (result.isObject() && result.has("allow")) {
            boolean allowed = result.get("allow").asBoolean(false);
            if (allowed) {
                return PolicyDecision.allow();
            }
 
            String reason = result.has("reason") ? result.get("reason").asText() : "Access denied by policy";
            List<String> violations = new ArrayList<>();
            if (result.has("violations") && result.get("violations").isArray()) {
                result.get("violations").forEach(v -> violations.add(v.asText()));
            }
            return PolicyDecision.deny(reason, violations);
        }
 
        return PolicyDecision.deny("Unexpected OPA response format");
    }
}
