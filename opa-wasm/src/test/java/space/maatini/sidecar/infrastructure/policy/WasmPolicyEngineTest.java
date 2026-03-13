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
            public ProxyConfig proxy() {
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
}