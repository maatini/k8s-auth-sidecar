package space.maatini.sidecar.infrastructure.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.domain.model.PolicyDecision;
import space.maatini.sidecar.domain.model.PolicyInput;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
                new PolicyInput.UserInfo("u1", "test@test", Set.of("user"), Collections.emptySet()),
                new PolicyInput.ResourceInfo("test", null, null),
                Collections.emptyMap());
    }

    @Test
    void testInit() {
        assertDoesNotThrow(() -> wasmPolicyEngine.init());
    }

    @Test
    void testEvaluateEmbeddedWasm_ModuleNotLoaded() {
        wasmPolicyEngine.loadWasmModule();
        PolicyDecision decision = wasmPolicyEngine.evaluateEmbeddedWasm(dummyInput).await().indefinitely();
        assertFalse(decision.allowed());
        assertEquals("WASM module not initialized", decision.reason());
    }

    @Test
    void testEvaluateEmbeddedWasm_Success() {
        SidecarConfig.OpaConfig opaConfig = new SidecarConfig.OpaConfig() {
            @Override
            public boolean enabled() {
                return true;
            }

            @Override
            public String defaultPackage() {
                return "authz";
            }

            @Override
            public String defaultRule() {
                return "allow";
            }

            @Override
            public EmbeddedOpaConfig embedded() {
                return new EmbeddedOpaConfig() {
                    @Override
                    public String wasmPath() {
                        return "classpath:policies/dummy.wasm";
                    }

                    @Override
                    public int poolSize() {
                        return 10;
                    }

                    @Override
                    public int poolAcquireTimeoutMs() {
                        return 50;
                    }
                };
            }

            @Override
            public HotReloadConfig hotReload() {
                return new HotReloadConfig() {
                    @Override
                    public boolean enabled() {
                        return false;
                    }

                    @Override
                    public String interval() {
                        return "10s";
                    }

                    @Override
                    public String delayed() {
                        return "5s";
                    }
                };
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
            public OpaConfig opa() {
                return opaConfig;
            }

            @Override
            public AuditConfig audit() {
                return null;
            }

            @Override
            public RolesConfig roles() {
                return null;
            }
        };

        wasmPolicyEngine.config = mockConfig;
        wasmPolicyEngine.loadWasmModule();

        PolicyDecision decision = wasmPolicyEngine.evaluateEmbeddedWasm(dummyInput).await().indefinitely();
        assertNotNull(decision);
        assertFalse(decision.allowed()); // Depends on dummy.wasm content
    }

    @Test
    void testResolvePathWithLeadingSlash() {
        Path path = wasmPolicyEngine.resolvePath("classpath:/policies/dummy.wasm");
        assertNotNull(path);
        assertTrue(path.toString().endsWith("dummy.wasm"));
    }

    @Test
    void testResolvePathClasspathNotFound() {
        Path path = wasmPolicyEngine.resolvePath("classpath:/non/existent.wasm");
        assertNull(path);
    }

    @Test
    void testResolvePathFileDirectly() {
        Path path = wasmPolicyEngine.resolvePath("/tmp/some-policy.wasm");
        assertEquals(Paths.get("/tmp/some-policy.wasm"), path);
    }

    @Test
    void testGetMaxModifiedTimeNonExistentDir() throws Exception {
        long time = wasmPolicyEngine.getMaxModifiedTime(Paths.get("/non/existent/dir"));
        assertEquals(0, time);
    }

    @Test
    void testGetMaxModifiedTimeWithFiles() throws Exception {
        Path tempDir = Files.createTempDirectory("wasm-test");
        try {
            Path regoFile = tempDir.resolve("test.rego");
            Files.writeString(regoFile, "package test");
            
            Path wasmFile = tempDir.resolve("test.wasm");
            Files.writeString(wasmFile, "wasm content");

            Path otherFile = tempDir.resolve("test.txt");
            Files.writeString(otherFile, "ignored content");

            long maxTime = wasmPolicyEngine.getMaxModifiedTime(tempDir);
            assertTrue(maxTime > 0);
            
            // Wait a bit and modify one
            Thread.sleep(100);
            Files.writeString(regoFile, "package test updated");
            long updatedMaxTime = wasmPolicyEngine.getMaxModifiedTime(tempDir);
            assertTrue(updatedMaxTime > maxTime);

        } finally {
            // Clean up
            Files.walk(tempDir)
                    .sorted((p1, p2) -> p2.compareTo(p1))
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
        }
    }

    @Test
    void testThreadLocalPolicyConsistency() {
        PolicyDecision d1 = wasmPolicyEngine.evaluateEmbeddedWasm(dummyInput).await().indefinitely();
        PolicyDecision d2 = wasmPolicyEngine.evaluateEmbeddedWasm(dummyInput).await().indefinitely();
        assertNotNull(d1);
        assertNotNull(d2);
    }
}