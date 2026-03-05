package space.maatini.sidecar.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.styra.opa.wasm.OpaPolicy;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import space.maatini.sidecar.common.config.SidecarConfig;
import space.maatini.sidecar.common.model.PolicyDecision;
import space.maatini.sidecar.common.model.PolicyInput;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
public class WasmPolicyEngine {

    private static final Logger LOG = Logger.getLogger(WasmPolicyEngine.class);

    @Inject
    SidecarConfig config;

    @Inject
    ObjectMapper objectMapper;

    // Thread-safe reference to the current WASM policy instance
    private final AtomicReference<OpaPolicy> wasmPolicyRef = new AtomicReference<>();
    private Thread watcherThread;
    private volatile boolean watching = true;

    @PostConstruct
    void init() {
        if (config.opa().enabled()) {
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
    }

    public boolean isModuleLoaded() {
        return wasmPolicyRef.get() != null;
    }

    public Uni<PolicyDecision> evaluateEmbeddedWasm(PolicyInput input) {
        OpaPolicy policy = wasmPolicyRef.get();
        if (policy == null) {
            LOG.warn("WASM policy module is not loaded. Defaulting to deny.");
            return Uni.createFrom().item(PolicyDecision.deny("WASM module not initialized"));
        }

        return Uni.createFrom().<PolicyDecision>deferred(() -> {
            try {
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
        });
    }

    /**
     * Loads the WASM module from the configured path into memory using chicory.
     */
    public void loadWasmModule() {
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

            OpaPolicy newPolicy = OpaPolicy.builder().withPolicy(wasmBytes).build();
            wasmPolicyRef.set(newPolicy);
            LOG.infof("Successfully loaded OPA WASM module from %s (%d bytes)", wasmPath, wasmBytes.length);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to load OPA WASM module from %s: %s", wasmPath, e.getMessage());
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

    /**
     * Starts a WatchService to monitor policy directories for changes.
     */
    protected void startHotReloadWatcher() {
        final Path watchDir = resolveWatchDir();
        if (watchDir == null) {
            LOG.info("No policy directory found to watch for hot-reloading.");
            return;
        }

        watcherThread = new Thread(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                watchDir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
                LOG.infof("Hot-reload watcher started on directory %s", watchDir.toAbsolutePath());

                while (watching) {
                    WatchKey key = watchService.take();
                    boolean changed = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        String file = event.context().toString();
                        if (file.endsWith(".rego") || file.endsWith(".wasm") || file.contains("..data")) {
                            changed = true;
                            break;
                        }
                    }
                    if (changed) {
                        LOG.info("Detected changes in policies directory. Recompiling and reloading...");
                        Thread.sleep(500); // Give filesystem time to settle
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

    protected void recompileWasm(Path policyDir) {
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
            if (outFile.getParentFile() != null) {
                outFile.getParentFile().mkdirs();
            }

            ProcessBuilder pb = new ProcessBuilder(
                    "sh", "-c",
                    String.format(
                            "opa build -t wasm -e %s/%s -o bundle.tar.gz %s && tar -xzOf bundle.tar.gz /policy.wasm > %s && rm bundle.tar.gz",
                            config.opa().defaultPackage(), config.opa().defaultRule(),
                            policyDir.toAbsolutePath(), outPath));
            pb.directory(policyDir.toFile());
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
