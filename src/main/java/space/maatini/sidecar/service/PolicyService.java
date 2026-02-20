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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import com.styra.opa.wasm.OpaPolicy;

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

    // Thread-safe reference to the current WASM policy instance
    private final AtomicReference<OpaPolicy> wasmPolicyRef = new AtomicReference<>();
    private Thread watcherThread;
    private volatile boolean watching = true;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);

        if (config.opa().enabled() && "embedded".equals(config.opa().mode())) {
            loadWasmModule();
            startHotReloadWatcher();
        }
    }

    @PreDestroy
    void shutdown() {
        watching = false;
        if (watcherThread != null) {
            watcherThread.interrupt();
        }
        if (webClient != null) {
            webClient.close();
        }
        // policy.close() - not needed if it doesn't hold native resources directly
        // (managed by JVM)
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
            return evaluateEmbeddedWasm(input);
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

    private Uni<PolicyDecision> evaluateEmbeddedWasm(PolicyInput input) {
        OpaPolicy policy = wasmPolicyRef.get();
        if (policy == null) {
            LOG.warn("WASM policy module is not loaded. Defaulting to deny.");
            return Uni.createFrom().item(PolicyDecision.deny("WASM module not initialized"));
        }

        return Uni.createFrom().item(() -> {
            try {
                String inputJson = objectMapper.writeValueAsString(input);
                String resultJson = policy.evaluate(inputJson);

                JsonNode resultNode = objectMapper.readTree(resultJson);
                if (resultNode.isArray() && resultNode.size() > 0) {
                    JsonNode resultObj = resultNode.get(0).get("result");
                    if (resultObj != null) {
                        if (resultObj.isBoolean()) {
                            return resultObj.asBoolean() ? PolicyDecision.allow()
                                    : PolicyDecision.deny("Access denied by policy");
                        }
                        if (resultObj.isObject() && resultObj.has("allow")) {
                            boolean allowed = resultObj.get("allow").asBoolean(false);
                            if (allowed) {
                                return PolicyDecision.allow();
                            }
                            return PolicyDecision.deny(resultObj.has("reason") ? resultObj.get("reason").asText()
                                    : "Access denied by policy");
                        }
                    }
                }

                if (resultNode.isBoolean()) {
                    return resultNode.asBoolean() ? PolicyDecision.allow()
                            : PolicyDecision.deny("Access denied by policy");
                }

                return PolicyDecision.deny("Unexpected WASM evaluation result");
            } catch (Exception e) {
                LOG.errorf(e, "WASM evaluation failed");
                throw new RuntimeException("WASM evaluation failed", e);
            }
        });
    }

    /**
     * Loads the WASM module from the configured path into memory using chicory.
     */
    private void loadWasmModule() {
        String wasmPath = config.opa().embedded().wasmPath();
        try {
            byte[] wasmBytes;
            if (wasmPath.startsWith("classpath:")) {
                String cpPath = wasmPath.substring("classpath:".length());
                if (cpPath.startsWith("/")) {
                    cpPath = cpPath.substring(1);
                }
                try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(cpPath)) {
                    if (is == null) {
                        throw new IOException("Resource not found in classpath: " + cpPath);
                    }
                    wasmBytes = is.readAllBytes();
                }
            } else {
                wasmBytes = Files.readAllBytes(Paths.get(wasmPath));
            }

            OpaPolicy newPolicy = OpaPolicy.builder().withPolicy(wasmBytes).build();
            wasmPolicyRef.set(newPolicy);
            LOG.infof("Successfully loaded OPA WASM module from %s", wasmPath);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to load OPA WASM module from %s. Requests will be denied.", wasmPath);
        }
    }

    /**
     * Starts a WatchService to monitor policy directories for changes.
     */
    private void startHotReloadWatcher() {
        Path devPolicies = Paths.get("src/main/resources/policies");
        Path prodPolicies = Paths.get("/policies");

        final Path watchDir;
        if (Files.exists(devPolicies)) {
            watchDir = devPolicies;
        } else if (Files.exists(prodPolicies)) {
            watchDir = prodPolicies;
        } else {
            LOG.info("No policy directory found to watch for hot-reloading.");
            return;
        }

        watcherThread = new Thread(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                watchDir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_CREATE);
                LOG.infof("Hot-reload watcher started on directory %s", watchDir.toAbsolutePath());

                while (watching) {
                    WatchKey key = watchService.take();
                    boolean changed = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        String file = event.context().toString();
                        if (file.endsWith(".rego") || file.endsWith(".wasm")) {
                            changed = true;
                            break;
                        }
                    }
                    if (changed) {
                        LOG.info("Detected changes in policies directory. Recompiling and reloading...");
                        Thread.sleep(500);
                        recompileWasm(watchDir);
                        loadWasmModule();
                    }
                    key.reset();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.info("Policy hot-reload watcher interrupted");
            } catch (Exception e) {
                LOG.errorf(e, "Error inside hot-reload watcher");
            }
        });
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    private void recompileWasm(Path policyDir) {
        try {
            ProcessBuilder checkPb = new ProcessBuilder("opa", "version");
            if (checkPb.start().waitFor() != 0) {
                return;
            }

            String outPath = config.opa().embedded().wasmPath();
            if (outPath.startsWith("classpath:")) {
                outPath = "target/classes/policies/authz.wasm";
            }

            File outFile = new File(outPath);
            outFile.getParentFile().mkdirs();

            ProcessBuilder pb = new ProcessBuilder(
                    "opa", "build", "-t", "wasm", "-e",
                    config.opa().defaultPackage() + "/" + config.opa().defaultRule(),
                    "-o", outPath, policyDir.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode == 0) {
                LOG.info("Successfully recompiled OPA policies to WASM");
            } else {
                LOG.errorf("Failed to compile OPA policies. Opa cli exit code: %d", exitCode);
            }
        } catch (Exception e) {
            LOG.debugf("Skipping recompilation. (opa cli might be missing): %s", e.getMessage());
        }
    }
}
