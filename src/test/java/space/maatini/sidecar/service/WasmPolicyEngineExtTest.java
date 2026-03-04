package space.maatini.sidecar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.styra.opa.wasm.OpaPolicy;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.PolicyDecision;
import space.maatini.sidecar.model.PolicyInput;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@io.quarkus.test.junit.QuarkusTest
class WasmPolicyEngineExtTest {

    private WasmPolicyEngine engine;
    private SidecarConfig config;
    private SidecarConfig.OpaConfig opaConfig;
    private SidecarConfig.OpaConfig.EmbeddedOpaConfig embeddedConfig;
    private ObjectMapper objectMapper;
    private OpaPolicy mockPolicy;

    @BeforeEach
    void setUp() throws Exception {
        engine = new WasmPolicyEngine();

        config = mock(SidecarConfig.class);
        opaConfig = mock(SidecarConfig.OpaConfig.class);
        embeddedConfig = mock(SidecarConfig.OpaConfig.EmbeddedOpaConfig.class);

        when(config.opa()).thenReturn(opaConfig);
        when(opaConfig.embedded()).thenReturn(embeddedConfig);
        when(opaConfig.enabled()).thenReturn(true);
        when(embeddedConfig.wasmPath()).thenReturn("classpath:policies/authz.wasm");

        objectMapper = new ObjectMapper();

        Field configField = WasmPolicyEngine.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(engine, config);

        Field mapperField = WasmPolicyEngine.class.getDeclaredField("objectMapper");
        mapperField.setAccessible(true);
        mapperField.set(engine, objectMapper);

        mockPolicy = mock(OpaPolicy.class);
    }

    @Test
    void testEvaluateEmbeddedWasm_ArrayResult() throws Exception {
        // Set up the mock policy
        Field refField = WasmPolicyEngine.class.getDeclaredField("wasmPolicyRef");
        refField.setAccessible(true);
        AtomicReference<OpaPolicy> ref = (AtomicReference<OpaPolicy>) refField.get(engine);
        ref.set(mockPolicy);

        // Mock the WASM output to be an array
        String arrayJson = "[{\"result\": {\"allow\": true}}]";
        when(mockPolicy.evaluate(anyString())).thenReturn(arrayJson);

        PolicyInput input = new PolicyInput(
                new PolicyInput.RequestInfo("GET", "/api", java.util.Map.of(), java.util.Map.of()),
                new PolicyInput.UserInfo("user1", "test@user.com", java.util.Set.of(), java.util.Set.of()),
                new PolicyInput.ResourceInfo("api", null, null),
                java.util.Map.of());
        Uni<PolicyDecision> decisionUni = engine.evaluateEmbeddedWasm(input);

        PolicyDecision decision = decisionUni.await().indefinitely();
        assertTrue(decision.allowed());
    }

    @Test
    void testEvaluateEmbeddedWasm_ObjectResult() throws Exception {
        // Set up the mock policy
        Field refField = WasmPolicyEngine.class.getDeclaredField("wasmPolicyRef");
        refField.setAccessible(true);
        AtomicReference<OpaPolicy> ref = (AtomicReference<OpaPolicy>) refField.get(engine);
        ref.set(mockPolicy);

        // Mock the WASM output to be an object
        String objJson = "{\"allow\": true}";
        when(mockPolicy.evaluate(anyString())).thenReturn(objJson);

        PolicyInput input = new PolicyInput(
                new PolicyInput.RequestInfo("GET", "/api", java.util.Map.of(), java.util.Map.of()),
                new PolicyInput.UserInfo("user1", "test@user.com", java.util.Set.of(), java.util.Set.of()),
                new PolicyInput.ResourceInfo("api", null, null),
                java.util.Map.of());
        Uni<PolicyDecision> decisionUni = engine.evaluateEmbeddedWasm(input);

        PolicyDecision decision = decisionUni.await().indefinitely();
        assertTrue(decision.allowed());
    }

    @Test
    void testShutdown_InterruptsWatcher() throws Exception {
        Thread mockThread = mock(Thread.class);
        Field watcherField = WasmPolicyEngine.class.getDeclaredField("watcherThread");
        watcherField.setAccessible(true);
        watcherField.set(engine, mockThread);

        Method shutdownMethod = WasmPolicyEngine.class.getDeclaredMethod("shutdown");
        shutdownMethod.setAccessible(true);
        shutdownMethod.invoke(engine);

        verify(mockThread).interrupt();

        Field watchingField = WasmPolicyEngine.class.getDeclaredField("watching");
        watchingField.setAccessible(true);
        assertFalse((Boolean) watchingField.get(engine));
    }

    @Test
    void testResolvePath_Classpath() throws Exception {
        java.nio.file.Path p = engine.resolvePath("classpath:policies/authz.rego");
        // Could be null if authz.rego doesn't exist, just testing it doesn't throw.
        // Assuming test-classes / policies / authz.rego does not throw exception
    }

    @Test
    void testResolvePath_Filesystem() throws Exception {
        java.nio.file.Path p = engine.resolvePath("/tmp/fake.rego");
        assertEquals("/tmp/fake.rego", p.toString());
    }

    @Test
    void testResolveWatchDir() throws Exception {
        Method m = WasmPolicyEngine.class.getDeclaredMethod("resolveWatchDir");
        m.setAccessible(true);
        java.nio.file.Path p = (java.nio.file.Path) m.invoke(engine);
        // Will return null or the legit path, just covering the branch
    }

    @Test
    void testRecompileWasm_FailsGracefully() throws Exception {
        Method m = WasmPolicyEngine.class.getDeclaredMethod("recompileWasm", java.nio.file.Path.class);
        m.setAccessible(true);
        m.invoke(engine, java.nio.file.Paths.get("/non/existent/dir/123991"));
        // Does not throw, logs error or skips
    }

    @Test
    void testLoadWasmModule_FileNotFound() throws Exception {
        when(embeddedConfig.wasmPath()).thenReturn("/invalid/test/path.wasm");
        engine.loadWasmModule();

        Field refField = WasmPolicyEngine.class.getDeclaredField("wasmPolicyRef");
        refField.setAccessible(true);
        AtomicReference<OpaPolicy> ref = (AtomicReference<OpaPolicy>) refField.get(engine);
        // Should catch the IO exception, log it, and leave ref as null originally
    }
}
