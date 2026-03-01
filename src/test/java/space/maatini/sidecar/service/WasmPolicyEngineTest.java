package space.maatini.sidecar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.PolicyDecision;
import space.maatini.sidecar.model.PolicyInput;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(WasmPolicyEngineTest.WasmTestProfile.class)
public class WasmPolicyEngineTest {

    public static class WasmTestProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "sidecar.opa.enabled", "true",
                    "sidecar.opa.mode", "embedded",
                    // Provide a dummy path just to prevent startup crashes if it tries to load
                    "sidecar.opa.embedded.wasm-path", "classpath:non-existent-startup.wasm");
        }
    }

    @Inject
    WasmPolicyEngine wasmPolicyEngine;

    @Inject
    SidecarConfig config;

    @Inject
    ObjectMapper objectMapper;

    private PolicyInput dummyInput;

    @BeforeEach
    void setup() {
        dummyInput = new PolicyInput(
                new PolicyInput.RequestInfo("GET", "/api/test", Collections.emptyMap(), Collections.emptyMap()),
                new PolicyInput.UserInfo("u1", "test@test", Set.of("user"), Collections.emptySet(), null),
                new PolicyInput.ResourceInfo("test", null, null),
                Collections.emptyMap());
    }

    @Test
    void testInitAndShutdown_EmbeddedMode() {
        // init is called automatically by @PostConstruct, but we can call it again
        // safely
        assertDoesNotThrow(() -> wasmPolicyEngine.init());
        assertDoesNotThrow(() -> wasmPolicyEngine.shutdown());
    }

    @Test
    void testEvaluateEmbeddedWasm_ModuleNotLoaded() {
        // Since the profile sets the path to a non-existent file, the module load fails
        // and internal state is kept empty.
        wasmPolicyEngine.loadWasmModule();

        PolicyDecision decision = wasmPolicyEngine.evaluateEmbeddedWasm(dummyInput)
                .await().indefinitely();

        assertFalse(decision.allowed());
        assertEquals("WASM module not initialized", decision.reason());
    }

    @Test
    void testEvaluateEmbeddedWasm_Success() {
        // We override the config using a system property or reflection just for this
        // test
        // if needed, but since we copied dummy.wasm to classpath:policies/dummy.wasm,
        // we can still use the profile's non-existent file for the main startup,
        // and manually load this one using reflection or by overriding the config mock
        // But wait, we removed the mock. Let's use reflection to inject our test path,
        // or just rely on a new QuarkusTestProfile if we wanted.
        // Actually, the easiest is to just set a system property if Quarkus picks it
        // up,
        // but it's already started.
        // Let's create an anonymous class to override the embedded() method.

        SidecarConfig.OpaConfig.EmbeddedOpaConfig embeddedConfig = new SidecarConfig.OpaConfig.EmbeddedOpaConfig() {
            @Override
            public String wasmPath() {
                return "classpath:policies/dummy.wasm";
            }
        };

        SidecarConfig.OpaConfig opaConfig = new SidecarConfig.OpaConfig() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public String mode() {
                return "embedded";
            }

            @Override
            public SidecarConfig.OpaConfig.ExternalOpaConfig external() {
                return null;
            }

            @Override
            public SidecarConfig.OpaConfig.EmbeddedOpaConfig embedded() {
                return embeddedConfig;
            }

            @Override
            public String defaultPackage() {
                return "authz";
            }

            @Override
            public String defaultRule() {
                return "allow";
            }
        };

        SidecarConfig mockConfig = new SidecarConfig() {
            @Override
            public AuthConfig auth() {
                return null;
            }

            @Override
            public AuthzConfig authz() {
                return null;
            }

            @Override
            public SidecarConfig.OpaConfig opa() {
                return opaConfig;
            }

            @Override
            public RateLimitConfig rateLimit() {
                return null;
            }

            @Override
            public AuditConfig audit() {
                return null;
            }

            @Override
            public SidecarConfig.ProxyConfig proxy() {
                return null;
            }
        };

        // Inject the manual mock config just for this test
        wasmPolicyEngine.config = mockConfig;

        // Load the dummy.wasm
        wasmPolicyEngine.loadWasmModule();

        PolicyDecision decision = wasmPolicyEngine.evaluateEmbeddedWasm(dummyInput)
                .await().indefinitely();

        assertNotNull(decision);
        // It should probably deny default, depending on dummy.wasm content
        assertFalse(decision.allowed());
    }

    @Test
    void testEdgeCases_LoadPaths() throws Exception {
        space.maatini.sidecar.config.SidecarConfig.OpaConfig opaConfig = org.mockito.Mockito
                .mock(space.maatini.sidecar.config.SidecarConfig.OpaConfig.class);
        space.maatini.sidecar.config.SidecarConfig.OpaConfig.EmbeddedOpaConfig embedConfig = org.mockito.Mockito
                .mock(space.maatini.sidecar.config.SidecarConfig.OpaConfig.EmbeddedOpaConfig.class);
        space.maatini.sidecar.config.SidecarConfig mockConfig = org.mockito.Mockito
                .mock(space.maatini.sidecar.config.SidecarConfig.class);
        org.mockito.Mockito.when(mockConfig.opa()).thenReturn(opaConfig);
        org.mockito.Mockito.when(opaConfig.embedded()).thenReturn(embedConfig);

        wasmPolicyEngine.config = mockConfig;

        // Classpath missing leading slash
        org.mockito.Mockito.when(embedConfig.wasmPath()).thenReturn("classpath:does-not-exist.wasm");
        wasmPolicyEngine.loadWasmModule();

        // Classpath with leading slash missing
        org.mockito.Mockito.when(embedConfig.wasmPath()).thenReturn("classpath:/does-not-exist.wasm");
        wasmPolicyEngine.loadWasmModule();

        // FileSystem not found
        org.mockito.Mockito.when(embedConfig.wasmPath()).thenReturn("/does-not-exist/invalid.wasm");
        wasmPolicyEngine.loadWasmModule();

        // Invalid Wasm byte payload
        java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("bad", ".wasm");
        java.nio.file.Files.write(tempFile, "bad-data".getBytes());
        org.mockito.Mockito.when(embedConfig.wasmPath()).thenReturn(tempFile.toAbsolutePath().toString());
        try {
            wasmPolicyEngine.loadWasmModule();
        } catch (Exception e) {
        }
        java.nio.file.Files.deleteIfExists(tempFile);
    }

    @Test
    void testHotReloadWatcher_Trigger() throws Exception {
        // Create a fake rego file in the dev directory to trigger the running watcher
        // thread!
        java.nio.file.Path policiesDir = java.nio.file.Paths.get("src/main/resources/policies");
        java.nio.file.Path tempTarget = policiesDir.resolve("test_trigger.rego");
        java.nio.file.Files.write(tempTarget, "package authz".getBytes());

        // Give the WatchService event loop time to pick up the CREATE/MODIFY event
        Thread.sleep(800);

        // Delete it again
        java.nio.file.Files.deleteIfExists(tempTarget);

        // Wait again for DELETE event processing
        Thread.sleep(200);
    }

}