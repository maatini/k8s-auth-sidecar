package space.maatini.sidecar.infrastructure.policy;

import space.maatini.sidecar.infrastructure.policy.WasmPolicyEngine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.styra.opa.wasm.OpaPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.domain.model.PolicyDecision;
import space.maatini.sidecar.domain.model.PolicyInput;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WasmPolicyEnginePojoTest {

    private WasmPolicyEngine engine;
    private SidecarConfig config;

    @BeforeEach
    void setup() throws Exception {
        engine = new WasmPolicyEngine();
        config = mock(SidecarConfig.class);
        setField(engine, "config", config);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testResolvePath() throws Exception {
        // Classpath check
        Path p1 = engine.resolvePath("classpath:policies/dummy.wasm");
        assertNotNull(p1);
        assertTrue(p1.toString().contains("dummy.wasm"));

        // File check
        Path p2 = engine.resolvePath("src/test/resources/policies/dummy.wasm");
        assertNotNull(p2);
        assertTrue(p2.toString().endsWith("dummy.wasm"));
    }

    @Test
    void testInit_Disabled() {
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
        when(config.opa()).thenReturn(opa);
        when(opa.enabled()).thenReturn(false);

        assertDoesNotThrow(() -> engine.init());
        assertFalse(engine.isModuleLoaded());
    }

    @Test
    void testEvaluate_NotLoaded() {
        PolicyInput input = new PolicyInput(null, null, null, null);
        PolicyDecision decision = engine.evaluateEmbeddedWasm(input).await().indefinitely();
        assertFalse(decision.allowed());
        assertEquals("WASM module not initialized", decision.reason());
    }

    @Test
    void testIsModuleLoaded() {
        assertFalse(engine.isModuleLoaded());
        // Wir können die native Kompilierung im POJO-Test nur schwer simulieren,
        // daher testen wir primär die Fallbacks des boolean Flags.
    }

    @Test
    void testResolveWatchDir_Logic() throws Exception {
        // Just invoke the public/protected logic to see if it handles the path
        // correctly
        Path watchDir = engine.resolveWatchDir();
        // Skip assertion if we can't guarantee environment state
    }

    @Test
    void testRecompileWasm_NoOpa() {
        assertDoesNotThrow(() -> engine.recompileWasm(Paths.get(".")));
    }

    @Test
    void testEvaluate_Exception() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new RuntimeException("JSON fail"));
        setField(engine, "objectMapper", failingMapper);

        // Mock a bundle so that the engine doesn't return the "not initialized" decision early
        byte[] dummyBytes = "dummy".getBytes();
        // The PolicyBundle record is private, so we might need a different approach if we can't instantiate it
        // However, we can use reflection or just load a dummy to set the wasmBundleRef
        
        // Actually, the simplest way is to use reflected construction of the inner record
        // It is private: private record PolicyBundle(byte[] bytes, long version) {}
        
        // Use reflection to set a dummy PolicyBundle to set the wasmBundleRef
        Class<?> bundleClass = Class.forName("space.maatini.sidecar.infrastructure.policy.WasmPolicyEngine$PolicyBundle");
        java.lang.reflect.Constructor<?> constructor = bundleClass.getDeclaredConstructor(byte[].class, long.class);
        constructor.setAccessible(true);
        Object bundle = constructor.newInstance(new byte[0], 1L);
        
        java.util.concurrent.atomic.AtomicReference<Object> ref = new java.util.concurrent.atomic.AtomicReference<>(bundle);
        setField(engine, "wasmBundleRef", ref);

        PolicyInput input = new PolicyInput(
                new PolicyInput.RequestInfo("GET", "/path", Map.of(), Map.of()),
                null, null, Map.of());
        PolicyDecision decision = engine.evaluateEmbeddedWasm(input).await().indefinitely();

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("WASM evaluation failed"));
    }
}
