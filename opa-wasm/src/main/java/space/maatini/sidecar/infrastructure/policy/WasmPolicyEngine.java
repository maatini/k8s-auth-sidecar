package space.maatini.sidecar.infrastructure.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.styra.opa.wasm.OpaPolicy;
import io.quarkus.scheduler.Scheduled;
import org.jboss.logging.Logger;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.domain.model.PolicyDecision;
import space.maatini.sidecar.domain.model.PolicyInput;
import space.maatini.sidecar.application.service.PolicyService;

import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Engine for evaluating OPA policies compiled to WASM.
 * This class handles thread-safe evaluation by maintaining a thread-local pool of WASM instances.
 */
@ApplicationScoped
public class WasmPolicyEngine {

    private static final Logger LOG = Logger.getLogger(WasmPolicyEngine.class);

    @Inject
    SidecarConfig config;

    @Inject
    ObjectMapper objectMapper;

    /**
     * Internal bundle of the WASM module and its version.
     */
    public record PolicyBundle(byte[] bytes, long version) {}

    /**
     * Per-thread instance of the compiled policy.
     */
    public record PolicyInstance(OpaPolicy policy, long version) {}

    private final AtomicReference<PolicyBundle> wasmBundleRef = new AtomicReference<>();
    private final ThreadLocal<PolicyInstance> threadPolicy = new ThreadLocal<>();
    private long lastModifiedTime = 0;

    @PostConstruct
    void init() {
        if (config.opa().enabled()) {
            loadWasmModule();
            // Initial modified time setup
            try {
                Path watchDir = resolveWatchDir();
                if (watchDir != null) {
                    lastModifiedTime = getMaxModifiedTime(watchDir);
                }
            } catch (Exception e) {
                LOG.debug("Could not initialize lastModifiedTime", e);
            }
        }
    }

    /**
     * Checks if the WASM module is loaded.
     */
    public boolean isModuleLoaded() {
        return wasmBundleRef.get() != null;
    }

    /**
     * Evaluates a policy using the embedded WASM engine.
     * Thread-safe: Retrieves or creates a thread-local WASM instance for evaluation.
     */
    public Uni<PolicyDecision> evaluateEmbeddedWasm(PolicyInput input) {
        PolicyBundle bundle = wasmBundleRef.get();
        if (bundle == null) {
            LOG.warn("WASM policy bundle is not loaded. Defaulting to deny.");
            return Uni.createFrom().item(PolicyDecision.deny("WASM module not initialized"));
        }

        return Uni.createFrom().deferred(() -> {
            try {
                OpaPolicy policy = getThreadLocalPolicy(bundle);
                String inputJson = objectMapper.writeValueAsString(input);
                String resultJson = policy.evaluate(inputJson);
                LOG.debugf("WASM evaluation result: %s", resultJson);

                if (resultJson == null) {
                    return Uni.createFrom().item(PolicyDecision.deny("WASM evaluation returned null result"));
                }

                JsonNode resultNode = objectMapper.readTree(resultJson);
                if (resultNode.isArray() && resultNode.size() > 0) {
                    JsonNode resultObj = resultNode.get(0).get("result");
                    return Uni.createFrom().item(PolicyService.parsePolicyResult(resultObj));
                }

                return Uni.createFrom().item(PolicyService.parsePolicyResult(resultNode));
            } catch (Exception e) {
                LOG.errorf(e, "WASM evaluation failed: %s", e.getMessage());
                return Uni.createFrom().item(PolicyDecision.deny("WASM evaluation failed: " + e.getMessage()));
            }
        }).emitOn(io.smallrye.mutiny.infrastructure.Infrastructure.getDefaultWorkerPool());
    }

    /**
     * Retrieves or creates a thread-local policy instance that matches the bundle version.
     */
    protected OpaPolicy getThreadLocalPolicy(PolicyBundle bundle) {
        PolicyInstance instance = threadPolicy.get();
        if (instance == null || instance.version() != bundle.version()) {
            LOG.debugf("Creating new OpaPolicy instance for thread %s (version %d)",
                    Thread.currentThread().getName(), bundle.version());
            OpaPolicy newPolicy = OpaPolicy.builder().withPolicy(bundle.bytes()).build();
            instance = new PolicyInstance(newPolicy, bundle.version());
            threadPolicy.set(instance);
        }
        return instance.policy();
    }

    /**
     * Loads the WASM module from the configured path.
     */
    public void loadWasmModule() {
        String wasmPath = config.opa().embedded().wasmPath();
        File tempWasm = new File("/tmp/authz.wasm");
        
        try {
            byte[] wasmBytes;
            // Hot-reload target in /tmp takes precedence if it exists
            if (tempWasm.exists()) {
                LOG.debugf("Loading WASM from hot-reload target: %s", tempWasm.getAbsolutePath());
                wasmBytes = Files.readAllBytes(tempWasm.toPath());
            } else if (wasmPath.startsWith("classpath:")) {
                String cpPath = wasmPath.substring("classpath:".length());
                if (cpPath.startsWith("/")) {
                    cpPath = cpPath.substring(1);
                }
                try (InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(cpPath)) {
                    if (is == null) {
                        LOG.errorf("Resource not found in classpath: %s", cpPath);
                        throw new IOException("Resource not found in classpath: " + cpPath);
                    }
                    wasmBytes = is.readAllBytes();
                }
            } else {
                Path path = Paths.get(wasmPath);
                if (!Files.exists(path)) {
                    LOG.errorf("WASM file not found: %s", wasmPath);
                    throw new IOException("WASM file not found: " + wasmPath);
                }
                wasmBytes = Files.readAllBytes(path);
            }

            long newVersion = wasmBundleRef.get() == null ? 1 : wasmBundleRef.get().version() + 1;
            wasmBundleRef.set(new PolicyBundle(wasmBytes, newVersion));
            LOG.infof("Successfully loaded OPA WASM bundle version %d (%d bytes)",
                    newVersion, wasmBytes.length);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to load OPA WASM module: %s", e.getMessage());
        }
    }

    public Path resolvePath(String path) {
        if (path.startsWith("classpath:")) {
            String cpPath = path.substring("classpath:".length());
            if (cpPath.startsWith("/")) {
                cpPath = cpPath.substring(1);
            }
            var url = Thread.currentThread().getContextClassLoader().getResource(cpPath);
            if (url == null)
                return null;
            try {
                return Paths.get(url.toURI());
            } catch (Exception e) {
                return null;
            }
        }
        return Paths.get(path);
    }

    @Scheduled(every = "{sidecar.opa.hot-reload.interval}", delayed = "5s", identity = "policy-hot-reload")
    void checkPolicyChanges() {
        if (!config.opa().enabled() || !config.opa().hotReload().enabled()) {
            return;
        }

        Path watchDir = resolveWatchDir();
        if (watchDir == null) {
            return;
        }

        try {
            long currentMaxModified = getMaxModifiedTime(watchDir);
            if (currentMaxModified > lastModifiedTime) {
                LOG.info("Detected changes in policies directory. Reloading WASM module...");
                lastModifiedTime = currentMaxModified;
                loadWasmModule();
            }
        } catch (IOException e) {
            LOG.errorf(e, "Error checking policy changes in %s", watchDir);
        }
    }

    protected long getMaxModifiedTime(Path dir) throws IOException {
        if (!Files.exists(dir)) return 0;
        try (var stream = Files.walk(dir, 1)) {
            return stream
                    .filter(p -> p.toString().endsWith(".wasm") || p.toString().contains("..data"))
                    .mapToLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .max()
                    .orElse(0L);
        }
    }

    protected Path resolveWatchDir() {
        Path devPolicies = Paths.get("src/main/resources/policies");
        Path prodPolicies = Paths.get("/policies");

        if (Files.exists(devPolicies)) {
            return devPolicies;
        } else if (Files.exists(prodPolicies)) {
            return prodPolicies;
        }
        return null;
    }
}
