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
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.model.PolicyDecision;
import space.maatini.sidecar.util.PathMatcher;
import space.maatini.sidecar.model.PolicyInput;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for evaluating authorization policies using OPA (Open Policy Agent).
 * Supports both embedded policy evaluation and external OPA server.
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

    private WebClient webClient;
    private final Map<String, String> policies = new ConcurrentHashMap<>();
    private WatchService watchService;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);

        if (config.opa().enabled() && "embedded".equals(config.opa().mode())) {
            loadEmbeddedPolicies();
            if (config.opa().embedded().watchPolicies()) {
                startPolicyWatcher();
            }
        }
    }

    @PreDestroy
    void shutdown() {
        if (watchService != null) {
            try {
                watchService.close();
                LOG.info("Policy watcher stopped");
            } catch (IOException e) {
                LOG.warnf("Failed to close policy watcher: %s", e.getMessage());
            }
        }
        if (webClient != null) {
            webClient.close();
        }
    }

    /**
     * Evaluates an authorization policy for the given context and request.
     *
     * @param authContext The authentication context
     * @param method      The HTTP method
     * @param path        The request path
     * @param headers     The request headers
     * @param queryParams The query parameters
     * @return A Uni containing the policy decision
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

        if ("external".equals(config.opa().mode())) {
            return evaluateExternal(input);
        } else {
            return evaluateEmbedded(input);
        }
    }

    /**
     * Evaluates a policy using an external OPA server.
     */
    private Uni<PolicyDecision> evaluateExternal(PolicyInput input) {
        String opaUrl = config.opa().external().url();
        String decisionPath = config.opa().external().decisionPath();
        int timeout = config.opa().external().timeout();

        try {
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.set("input", objectMapper.valueToTree(input));

            return webClient.postAbs(opaUrl + decisionPath)
                    .timeout(timeout)
                    .sendJson(requestBody)
                    .onItem().transform(this::parseOpaResponse)
                    .onFailure().recoverWithItem(error -> {
                        LOG.errorf("OPA evaluation failed: %s", error.getMessage());
                        // Fail closed: deny on error
                        return PolicyDecision.deny("Policy evaluation failed: " + error.getMessage());
                    });
        } catch (Exception e) {
            LOG.errorf(e, "Failed to build OPA request");
            return Uni.createFrom().item(
                    PolicyDecision.deny("Policy evaluation error: " + e.getMessage()));
        }
    }

    /**
     * Evaluates a policy using embedded policy logic.
     * This is a simplified implementation - in production, you'd use the OPA SDK.
     */
    @CacheResult(cacheName = "policy-decision-cache")
    public Uni<PolicyDecision> evaluateEmbedded(PolicyInput input) {
        return Uni.createFrom().item(() -> {
            try {
                // Simple embedded policy evaluation
                // In a real implementation, you'd integrate the OPA SDK or use Rego evaluation
                return evaluateSimplePolicies(input);
            } catch (Exception e) {
                LOG.errorf(e, "Embedded policy evaluation failed");
                return PolicyDecision.deny("Policy evaluation error: " + e.getMessage());
            }
        });
    }

    /**
     * Simple policy evaluation logic.
     * This implements basic RBAC checks - extend as needed.
     */
    private PolicyDecision evaluateSimplePolicies(PolicyInput input) {
        // Check if user is authenticated
        if (input.user() == null || input.user().id() == null) {
            return PolicyDecision.deny("User not authenticated");
        }

        // Allow superadmin role to access everything
        if (input.user().roles() != null && input.user().roles().contains("superadmin")) {
            return PolicyDecision.allow(Map.of("reason", "superadmin access"));
        }

        // Path-based role checking
        String path = input.request().path();
        String method = input.request().method();

        // Admin paths require admin role
        if (path.startsWith("/api/admin")) {
            if (hasRequiredRole(input.user().roles(), "admin")) {
                return PolicyDecision.allow();
            }
            return PolicyDecision.deny("Admin role required",
                    List.of("Missing required role: admin"));
        }

        // User management requires user-manager or admin role
        if (path.startsWith("/api/users")
                && ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))) {
            if (hasRequiredRole(input.user().roles(), "admin", "user-manager")) {
                return PolicyDecision.allow();
            }
            return PolicyDecision.deny("User management role required",
                    List.of("Missing required role: admin or user-manager"));
        }

        // Read-only access for authenticated users
        if ("GET".equals(method) && path.startsWith("/api/")) {
            if (hasRequiredRole(input.user().roles(), "user", "admin", "viewer")) {
                return PolicyDecision.allow();
            }
        }

        // Public endpoints
        if (isPublicPath(path)) {
            return PolicyDecision.allow(Map.of("reason", "public endpoint"));
        }

        // Default: check if user has 'user' role for general API access
        if (path.startsWith("/api/") && hasRequiredRole(input.user().roles(), "user", "admin")) {
            return PolicyDecision.allow();
        }

        // Default deny
        return PolicyDecision.deny("Access denied by default policy",
                List.of("No matching policy found for path: " + path));
    }

    /**
     * Checks if the user has any of the required roles.
     */
    private boolean hasRequiredRole(Set<String> userRoles, String... requiredRoles) {
        if (userRoles == null || userRoles.isEmpty()) {
            return false;
        }
        for (String role : requiredRoles) {
            if (userRoles.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a path is public (no authentication required).
     */
    private boolean isPublicPath(String path) {
        return PathMatcher.matchesAny(path, config.auth().publicPaths());
    }

    /**
     * Parses the response from an external OPA server.
     */
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

            // Handle boolean result
            if (result.isBoolean()) {
                return result.asBoolean()
                        ? PolicyDecision.allow()
                        : PolicyDecision.deny("Access denied by policy");
            }

            // Handle object result with 'allow' field
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
     * Loads embedded policies from the configured directory.
     */
    private void loadEmbeddedPolicies() {
        String policyDir = config.opa().embedded().policyDirectory();
        LOG.infof("Loading embedded policies from: %s", policyDir);

        try {
            Path path = Paths.get(policyDir);
            if (!Files.exists(path)) {
                LOG.warnf("Policy directory does not exist: %s", policyDir);
                // Try to load from classpath
                loadPoliciesFromClasspath();
                return;
            }

            Files.walk(path)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".rego"))
                    .forEach(this::loadPolicy);

            LOG.infof("Loaded %d policies", policies.size());
        } catch (IOException e) {
            LOG.errorf(e, "Failed to load policies from directory: %s", policyDir);
        }
    }

    /**
     * Loads policies from the classpath.
     */
    private void loadPoliciesFromClasspath() {
        try {
            // Load default policy from classpath
            var resource = getClass().getClassLoader().getResourceAsStream("policies/authz.rego");
            if (resource != null) {
                String content = new String(resource.readAllBytes());
                policies.put("authz", content);
                LOG.info("Loaded default policy from classpath");
            }
        } catch (IOException e) {
            LOG.warnf("Failed to load policies from classpath: %s", e.getMessage());
        }
    }

    /**
     * Loads a single policy file.
     */
    private void loadPolicy(Path policyPath) {
        try {
            String content = Files.readString(policyPath);
            String name = policyPath.getFileName().toString().replace(".rego", "");
            policies.put(name, content);
            LOG.debugf("Loaded policy: %s", name);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to load policy: %s", policyPath);
        }
    }

    /**
     * Starts a file watcher for hot-reloading policies.
     */
    private void startPolicyWatcher() {
        String policyDir = config.opa().embedded().policyDirectory();
        try {
            Path path = Paths.get(policyDir);
            if (!Files.exists(path)) {
                return;
            }

            watchService = FileSystems.getDefault().newWatchService();
            path.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);

            // Start watcher thread
            Thread watchThread = new Thread(this::watchPolicies, "policy-watcher");
            watchThread.setDaemon(true);
            watchThread.start();

            LOG.info("Policy watcher started");
        } catch (IOException e) {
            LOG.errorf(e, "Failed to start policy watcher");
        }
    }

    /**
     * Watches for policy file changes.
     */
    private void watchPolicies() {
        while (true) {
            try {
                WatchKey key = watchService.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changedPath = (Path) event.context();
                    if (changedPath.toString().endsWith(".rego")) {
                        LOG.infof("Policy change detected: %s", changedPath);
                        loadEmbeddedPolicies();
                    }
                }
                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
}
