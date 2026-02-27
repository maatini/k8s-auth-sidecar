package space.maatini.sidecar.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.cache.CacheResult;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.model.PolicyDecision;
import space.maatini.sidecar.model.PolicyInput;

import java.util.*;

/**
 * Service for evaluating authorization policies using OPA (Open Policy Agent).
 * Supports external OPA server, or embedded mode (spawns an actual OPA
 * process).
 */
@ApplicationScoped
public class PolicyService {

    private static final Logger LOG = Logger.getLogger(PolicyService.class);

    @Inject
    SidecarConfig config;

    @Inject
    Vertx vertx;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    WasmPolicyEngine wasmEngine;

    @Inject
    PolicyService self; // Self-injection for interceptors to work on evaluations

    private WebClient webClient;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    @PreDestroy
    void shutdown() {
        if (webClient != null) {
            webClient.close();
        }
    }

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
        return self.evaluateExternal(input);
    }

    /**
     * Evaluates a policy using the OPA HTTP API (either external or locally
     * embedded server).
     */
    @CacheResult(cacheName = "policy-decision-cache")
    @Retry(maxRetries = 2, delay = 200, retryOn = Exception.class)
    @Timeout(3000)
    @CircuitBreaker(requestVolumeThreshold = 10, failureRatio = 0.5, delay = 5000)
    @Fallback(fallbackMethod = "fallbackEvaluateExternal")
    public Uni<PolicyDecision> evaluateExternal(PolicyInput input) {
        if ("embedded".equals(config.opa().mode())) {
            return wasmEngine.evaluateEmbeddedWasm(input);
        }

        String opaUrl = config.opa().external().url();
        String decisionPath = config.opa().external().decisionPath();

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.set("input", objectMapper.valueToTree(input));

            return webClient.postAbs(opaUrl + decisionPath)
                    .sendJson(requestBody)
                    .onItem().transform(this::parseOpaResponse)
                    .onFailure().invoke(error -> LOG.errorf("OPA request failed: %s", error.getMessage()));
        } catch (Exception e) {
            LOG.errorf(e, "Failed to build OPA request");
            throw new RuntimeException("Policy evaluation error: " + e.getMessage(), e);
        }
    }

    public Uni<PolicyDecision> fallbackEvaluateExternal(PolicyInput input, Throwable t) {
        LOG.warnf("Fallback triggered for OPA evaluation on path %s: %s", input.request().path(), t.getMessage());
        return Uni.createFrom().item(PolicyDecision.deny("Policy subsystem unavailable. Access denied for security."));
    }

    /**
     * Parses a JSON result node from OPA into a PolicyDecision.
     * Works for both external OPA HTTP responses and embedded WASM results.
     */
    static PolicyDecision parsePolicyResult(JsonNode result) {
        if (result == null) {
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

    private PolicyDecision parseOpaResponse(HttpResponse<Buffer> response) {
        if (response.statusCode() != 200) {
            return PolicyDecision.deny("OPA returned status: " + response.statusCode());
        }

        try {
            JsonNode body = objectMapper.readTree(response.bodyAsString());
            return parsePolicyResult(body.get("result"));
        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse OPA response");
            return PolicyDecision.deny("Failed to parse OPA response: " + e.getMessage());
        }
    }
}
