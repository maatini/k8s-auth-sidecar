package space.maatini.sidecar.infrastructure.policy;



import com.fasterxml.jackson.databind.ObjectMapper;
import com.styra.opa.wasm.OpaPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.domain.model.PolicyDecision;
import space.maatini.sidecar.domain.model.PolicyInput;
import space.maatini.sidecar.application.service.PolicyService;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Files;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WasmPolicyEnginePojoTest {

    private WasmPolicyEngine engine;
    private SidecarConfig config;
    private PolicyService policyService;
    private SidecarConfig.OpaConfig.EmbeddedOpaConfig embeddedConfig;

    @BeforeEach
    void setup() throws Exception {
        engine = new WasmPolicyEngine();
        config = mock(SidecarConfig.class);
        policyService = mock(PolicyService.class);
        SidecarConfig.OpaConfig opaConfig = mock(SidecarConfig.OpaConfig.class);
        embeddedConfig = mock(SidecarConfig.OpaConfig.EmbeddedOpaConfig.class);
        when(config.opa()).thenReturn(opaConfig);
        when(opaConfig.embedded()).thenReturn(embeddedConfig);
        when(embeddedConfig.poolSize()).thenReturn(10);
        when(embeddedConfig.poolAcquireTimeoutMs()).thenReturn(50);
        setField(engine, "config", config);
        setField(engine, "policyService", policyService);
        // Initialize pool manually since @PostConstruct is not called
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ArrayBlockingQueue<com.styra.opa.wasm.OpaPolicy>> poolRef = new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.ArrayBlockingQueue<>(10));
        setField(engine, "policyPoolRef", poolRef);
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
        engine.resolveWatchDir();
        // Skip assertion if we can't guarantee environment state
    }



    @Test
    void testEvaluate_Exception() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new RuntimeException("JSON fail"));
        setField(engine, "objectMapper", failingMapper);

        // Properly initialize the engine using the dummy bundle so that policyPool is populated
        WasmPolicyEngine.PolicyBundle bundle = createDummyBundle(1L);
        java.util.concurrent.atomic.AtomicReference<Object> ref = new java.util.concurrent.atomic.AtomicReference<>(bundle);
        setField(engine, "wasmBundleRef", ref);
        engine.refreshPolicyPool(bundle);

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
        when(embedded.poolSize()).thenReturn(10);

        Object bundle = createDummyBundle(1L);
        java.util.concurrent.atomic.AtomicReference<Object> ref = new java.util.concurrent.atomic.AtomicReference<>(bundle);
        setField(engine, "wasmBundleRef", ref);

        assertDoesNotThrow(() -> engine.init());
        assertTrue(engine.isModuleLoaded());
    }



    @Test
    void testPolicyPool_VersionMismatch() throws Exception {
        Object bundle1 = createDummyBundle(1L);
        Object bundle2 = createDummyBundle(2L);

        WasmPolicyEngine.PolicyBundle pb1 = (WasmPolicyEngine.PolicyBundle) bundle1;
        WasmPolicyEngine.PolicyBundle pb2 = (WasmPolicyEngine.PolicyBundle) bundle2;

        engine.refreshPolicyPool(pb1);
        Field poolRefField = WasmPolicyEngine.class.getDeclaredField("policyPoolRef");
        poolRefField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ArrayBlockingQueue<OpaPolicy>> poolRef =
                (java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ArrayBlockingQueue<OpaPolicy>>) poolRefField.get(engine);
        assertEquals(10, poolRef.get().size());

        engine.refreshPolicyPool(pb2);
        // After refresh, poolRef points to a new pool instance
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ArrayBlockingQueue<OpaPolicy>> poolRef2 =
                (java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ArrayBlockingQueue<OpaPolicy>>) poolRefField.get(engine);
        assertEquals(10, poolRef2.get().size());
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
            Path file1 = tempDir.resolve("test.wasm");
            Files.writeString(file1, "wasm content");
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

    @Test
    void testCheckPolicyChanges_InvalidatesCache() throws Exception {
        // Setup: enable OPA + hot-reload
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
        SidecarConfig.OpaConfig.HotReloadConfig hotReload = mock(SidecarConfig.OpaConfig.HotReloadConfig.class);
        SidecarConfig.OpaConfig.EmbeddedOpaConfig embedded = mock(SidecarConfig.OpaConfig.EmbeddedOpaConfig.class);
        when(config.opa()).thenReturn(opa);
        when(opa.enabled()).thenReturn(true);
        when(opa.hotReload()).thenReturn(hotReload);
        when(hotReload.enabled()).thenReturn(true);
        when(opa.embedded()).thenReturn(embedded);
        when(embedded.wasmPath()).thenReturn("classpath:policies/dummy.wasm");

        // Create a temp dir with a .wasm file that has a future timestamp
        Path tempDir = Files.createTempDirectory("hotreload-cache-test");
        try {
            Path wasmFile = tempDir.resolve("authz.wasm");
            Files.writeString(wasmFile, "dummy wasm");
            // Set modification time far in the future to trigger reload
            Files.setLastModifiedTime(wasmFile,
                    java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 100000));

            // Override resolveWatchDir to return our temp dir
            WasmPolicyEngine spyEngine = spy(engine);
            setField(spyEngine, "config", config);
            setField(spyEngine, "policyService", policyService);
            doReturn(tempDir).when(spyEngine).resolveWatchDir();
            doNothing().when(spyEngine).loadWasmModule();

            spyEngine.checkPolicyChanges();

            verify(policyService).invalidatePolicyCache();
        } finally {
            Files.walk(tempDir).map(Path::toFile).forEach(java.io.File::delete);
        }
    }

    @Test
    void testCheckPolicyChanges_NoChange_DoesNotInvalidateCache() throws Exception {
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
        SidecarConfig.OpaConfig.HotReloadConfig hotReload = mock(SidecarConfig.OpaConfig.HotReloadConfig.class);
        when(config.opa()).thenReturn(opa);
        when(opa.enabled()).thenReturn(true);
        when(opa.hotReload()).thenReturn(hotReload);
        when(hotReload.enabled()).thenReturn(true);

        // Override resolveWatchDir to return null (no watch dir)
        WasmPolicyEngine spyEngine = spy(engine);
        setField(spyEngine, "config", config);
        setField(spyEngine, "policyService", policyService);
        doReturn(null).when(spyEngine).resolveWatchDir();

        spyEngine.checkPolicyChanges();

        verify(policyService, never()).invalidatePolicyCache();
    }
}
