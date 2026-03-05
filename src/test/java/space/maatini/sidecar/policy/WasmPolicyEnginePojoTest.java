package space.maatini.sidecar.policy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.common.config.SidecarConfig;
import space.maatini.sidecar.common.model.PolicyDecision;
import space.maatini.sidecar.common.model.PolicyInput;

import java.lang.reflect.Field;
import java.nio.file.Path;

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
    void testResolveWatchDir() throws Exception {
        java.lang.reflect.Method method = engine.getClass().getDeclaredMethod("resolveWatchDir");
        method.setAccessible(true);

        Path res1 = (Path) method.invoke(engine);
        assertNotNull(res1);
        assertTrue(res1.toString().endsWith("policies"));
    }
}
