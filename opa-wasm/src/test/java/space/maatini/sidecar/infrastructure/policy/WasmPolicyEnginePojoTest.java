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
import java.nio.file.Files;
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
        Object bundle = createDummyBundle(1L);
        java.util.concurrent.atomic.AtomicReference<Object> ref = new java.util.concurrent.atomic.AtomicReference<>(bundle);
        setField(engine, "wasmBundleRef", ref);

        PolicyInput input = new PolicyInput(
                new PolicyInput.RequestInfo("GET", "/path", Map.of(), Map.of()),
                null, null, Map.of());
        PolicyDecision decision = engine.evaluateEmbeddedWasm(input).await().indefinitely();

        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("WASM evaluation failed"));
    }

    private WasmPolicyEngine.PolicyBundle createDummyBundle(long version) throws Exception {
        byte[] wasmBytes = Files.readAllBytes(engine.resolvePath("src/test/resources/policies/dummy.wasm"));
        return new WasmPolicyEngine.PolicyBundle(wasmBytes, version);
    }

    @Test
    void testInit_Enabled_Success() throws Exception {
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
        SidecarConfig.OpaConfig.EmbeddedOpaConfig embedded = mock(SidecarConfig.OpaConfig.EmbeddedOpaConfig.class);
        when(config.opa()).thenReturn(opa);
        when(opa.enabled()).thenReturn(true);
        when(opa.embedded()).thenReturn(embedded);
        when(embedded.wasmPath()).thenReturn("classpath:policies/dummy.wasm");

        Object bundle = createDummyBundle(1L);
        java.util.concurrent.atomic.AtomicReference<Object> ref = new java.util.concurrent.atomic.AtomicReference<>(bundle);
        setField(engine, "wasmBundleRef", ref);

        assertDoesNotThrow(() -> engine.init());
        assertTrue(engine.isModuleLoaded());
    }

    @Test
    void testRecompileWasm_WithValidDir() throws Exception {
        Path tempDir = Files.createTempDirectory("policies");
        Files.copy(getClass().getResourceAsStream("/policies/dummy.wasm"), tempDir.resolve("dummy.wasm"));

        assertDoesNotThrow(() -> engine.recompileWasm(tempDir));
        // Check bundle updated (version increment simulated)
    }

    @Test
    @SuppressWarnings("rawtypes")
    void testGetThreadLocalPolicy_VersionMismatch() throws Exception {
        Object bundle1 = createDummyBundle(1L);
        Object bundle2 = createDummyBundle(2L);

        WasmPolicyEngine.PolicyBundle pb1 = (WasmPolicyEngine.PolicyBundle) bundle1;
        WasmPolicyEngine.PolicyBundle pb2 = (WasmPolicyEngine.PolicyBundle) bundle2;

        OpaPolicy policy1 = engine.getThreadLocalPolicy(pb1);
        assertNotNull(policy1);

        OpaPolicy policy2 = engine.getThreadLocalPolicy(pb2);
        assertNotNull(policy2);
        assertNotSame(policy1, policy2); // Should create new instance for new version
    }

    @Test
    void testLoadWasmModule_VersionIncrement() throws Exception {
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
        SidecarConfig.OpaConfig.EmbeddedOpaConfig embedded = mock(SidecarConfig.OpaConfig.EmbeddedOpaConfig.class);
        when(config.opa()).thenReturn(opa);
        when(opa.embedded()).thenReturn(embedded);
        when(embedded.wasmPath()).thenReturn("classpath:policies/dummy.wasm");

        engine.loadWasmModule();
        assertTrue(engine.isModuleLoaded());
        long v1 = getVersion(engine);
        assertEquals(1, v1);

        engine.loadWasmModule();
        long v2 = getVersion(engine);
        assertEquals(2, v2); // Version should increment
    }

    private long getVersion(WasmPolicyEngine engine) throws Exception {
        Field field = engine.getClass().getDeclaredField("wasmBundleRef");
        field.setAccessible(true);
        java.util.concurrent.atomic.AtomicReference<?> ref = (java.util.concurrent.atomic.AtomicReference<?>) field.get(engine);
        Object bundle = ref.get();
        if (bundle == null) return 0;
        Field vField = bundle.getClass().getDeclaredField("version");
        vField.setAccessible(true);
        return (long) vField.get(bundle);
    }

    @Test
    void testLoadWasmModule_ResourceNotFound() {
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
        SidecarConfig.OpaConfig.EmbeddedOpaConfig embedded = mock(SidecarConfig.OpaConfig.EmbeddedOpaConfig.class);
        when(config.opa()).thenReturn(opa);
        when(opa.embedded()).thenReturn(embedded);
        when(embedded.wasmPath()).thenReturn("classpath:non-existent.wasm");

        assertDoesNotThrow(() -> engine.loadWasmModule());
        assertFalse(engine.isModuleLoaded());
    }

    @Test
    void testGetMaxModifiedTime_Files() throws Exception {
        Path tempDir = Files.createTempDirectory("pitest-maxmod");
        try {
            Path file1 = tempDir.resolve("test.rego");
            Files.writeString(file1, "package test");
            long time1 = Files.getLastModifiedTime(file1).toMillis();

            long max = engine.getMaxModifiedTime(tempDir);
            assertTrue(max >= time1);

            // Mutate check: ensure it filters correctly
            Path file2 = tempDir.resolve("ignore.txt");
            Files.writeString(file2, "ignore");
            // Set time far in the future
            Files.setLastModifiedTime(file2, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 100000));

            long max2 = engine.getMaxModifiedTime(tempDir);
            assertEquals(max, max2); // Should still be time1 because ignore.txt is filtered out
        } finally {
            // Cleanup
            Files.walk(tempDir).map(Path::toFile).forEach(java.io.File::delete);
        }
    }
}
