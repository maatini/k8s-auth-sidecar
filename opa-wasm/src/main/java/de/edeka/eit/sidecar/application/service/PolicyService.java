package de.edeka.eit.sidecar.application.service;
 
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.cache.CacheInvalidateAll;
import io.quarkus.cache.CacheResult;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import de.edeka.eit.sidecar.infrastructure.config.SidecarConfig;
import de.edeka.eit.sidecar.domain.model.AuthContext;
import de.edeka.eit.sidecar.domain.model.PolicyDecision;
import de.edeka.eit.sidecar.domain.model.PolicyInput;
import de.edeka.eit.sidecar.domain.model.PolicyCacheKey;
import de.edeka.eit.sidecar.infrastructure.policy.WasmPolicyEngine;
 
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
 
/**
 * Service for evaluating authorization policies using embedded OPA WASM.
 * Implements the PolicyEngine interface from auth-core.
 *
 * <p><b>Thundering Herd Risk (Cache Stampede):</b>
 * After a WASM policy hot-reload, {@link #invalidatePolicyCache()} calls
 * {@code @CacheInvalidateAll} which evicts all cached decisions at once.
 * This causes every concurrent request to re-evaluate WASM simultaneously,
 * potentially exhausting the WASM instance pool. Mitigations to consider:
 * <ul>
 *   <li>Probabilistic early expiration (staggered TTL per cache entry)</li>
 *   <li>A cache lock / single-flight pattern to coalesce duplicate evaluations</li>
 *   <li>Increasing the WASM pool size proportionally to expected concurrent requests</li>
 * </ul>
 * </p>
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
        PolicyCacheKey key = new PolicyCacheKey(
                authContext.userId(),
                authContext.roles(),
                authContext.permissions(),
                method,
                path
        );
        return evaluatePolicy(key, input);
    }
 
    /** In-flight tracker: ensures only one WASM evaluation per cache key runs concurrently. */
    private final ConcurrentHashMap<PolicyCacheKey, Uni<PolicyDecision>> inFlight =
            new ConcurrentHashMap<>();

    @CacheResult(cacheName = "policy-decision-cache")
    public Uni<PolicyDecision> evaluatePolicy(@io.quarkus.cache.CacheKey PolicyCacheKey key, PolicyInput input) {
        // Single-flight: coalesce duplicate in-flight evaluations for the same key
        return Uni.createFrom().deferred(() -> {
            Uni<PolicyDecision> existing = inFlight.get(key);
            if (existing != null) {
                return existing;
            }
            Uni<PolicyDecision> evaluation = wasmEngine.evaluateEmbeddedWasm(input)
                    .onFailure().recoverWithItem(error -> {
                        LOG.warnf("WASM evaluation failed on path %s: %s",
                                input.request().path(), error.getMessage());
                        return PolicyDecision.deny(
                                "Policy evaluation failed. Access denied for security.");
                    })
                    .onTermination().invoke(() -> inFlight.remove(key))
                    .memoize().indefinitely();
            Uni<PolicyDecision> winner = inFlight.putIfAbsent(key, evaluation);
            return winner != null ? winner : evaluation;
        });
    }
 
    /**
     * Invalidates all entries in the policy decision cache.
     * Called after a successful WASM module hot-reload to prevent stale authorization decisions.
     */
    @CacheInvalidateAll(cacheName = "policy-decision-cache")
    public void invalidatePolicyCache() {
        LOG.info("Policy decision cache invalidated after WASM reload");
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
