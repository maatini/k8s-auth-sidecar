package space.maatini.sidecar.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.cache.CacheResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import space.maatini.sidecar.common.config.SidecarConfig;
import space.maatini.sidecar.common.model.AuthContext;
import space.maatini.sidecar.common.model.PolicyDecision;
import space.maatini.sidecar.common.model.PolicyInput;

import java.util.*;

/**
 * Service for evaluating authorization policies using embedded OPA WASM
 * (Chicory).
 */
@ApplicationScoped
public class PolicyService {

    private static final Logger LOG = Logger.getLogger(PolicyService.class);

    @Inject
    SidecarConfig config;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WasmPolicyEngine wasmEngine;

    /**
     * Evaluates an authorization policy for the given context and request.
     */
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

    /**
     * Evaluates a policy using the embedded OPA WASM engine.
     */
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

    /**
     * Parses a JSON result node from OPA into a PolicyDecision.
     * Works for embedded WASM results.
     */
    static PolicyDecision parsePolicyResult(JsonNode result) {
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
