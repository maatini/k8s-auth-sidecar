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
import space.maatini.sidecar.util.PathMatcher;

import java.io.IOException;
import java.nio.file.*;
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
    PolicyService self; // Self-injection for interceptors to work on evaluations

    private WebClient webClient;
    private Process embeddedOpaProcess;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);

        if (config.opa().enabled() && "embedded".equals(config.opa().mode())) {
            startEmbeddedOpaDaemon();
        }
    }

    @PreDestroy
    void shutdown() {
        if (embeddedOpaProcess != null && embeddedOpaProcess.isAlive()) {
            LOG.info("Shutting down embedded OPA process");
            embeddedOpaProcess.destroy();
        }
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
        String opaUrl = config.opa().external().url();
        String decisionPath = config.opa().external().decisionPath();

        // If in embedded mode, we override the URL to point to our locally spawned OPA
        // daemon
        if ("embedded".equals(config.opa().mode())) {
            opaUrl = "http://localhost:8181";
        }

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

    private PolicyDecision parseOpaResponse(HttpResponse<Buffer> response) {
        if (response.statusCode() != 200) {
            return PolicyDecision.deny("OPA returned status: " + response.statusCode());
        }

        try {
            JsonNode body = objectMapper.readTree(response.bodyAsString());
            JsonNode result = body.get("result");

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
                String reason = result.has("reason") ? result.get("reason").asText() : null;

                if (allowed) {
                    return PolicyDecision.allow();
                } else {
                    List<String> violations = new ArrayList<>();
                    if (result.has("violations") && result.get("violations").isArray()) {
                        result.get("violations").forEach(v -> violations.add(v.asText()));
                    }
                    return PolicyDecision.deny(reason, violations);
                }
            }
            return PolicyDecision.deny("Unexpected OPA response format");
        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse OPA response");
            return PolicyDecision.deny("Failed to parse OPA response: " + e.getMessage());
        }
    }

    /**
     * Starts an authentic OPA process locally for real Rego evaluation instead of
     * Java simulation.
     */
    private void startEmbeddedOpaDaemon() {
        String policyDir = config.opa().embedded().policyDirectory();
        LOG.infof("Starting embedded OPA daemon on port 8181 monitoring policies in %s", policyDir);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "opa", "run", "--server", "--addr", "localhost:8181", policyDir);
            // Ignore output or redirect to sidecar output
            pb.redirectErrorStream(true);
            embeddedOpaProcess = pb.start();
            LOG.info("Embedded OPA daemon started successfully.");
        } catch (IOException e) {
            LOG.errorf("Failed to start embedded OPA daemon. Ensure 'opa' CLI is installed and on the PATH: %s",
                    e.getMessage());
            // We don't crash the sidecar here, as requests will just Fallback to Deny.
        }
    }
}
