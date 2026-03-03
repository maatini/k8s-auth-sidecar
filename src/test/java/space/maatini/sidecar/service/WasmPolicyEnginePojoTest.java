package space.maatini.sidecar.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.styra.opa.wasm.OpaPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.PolicyDecision;
import space.maatini.sidecar.model.PolicyInput;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WasmPolicyEnginePojoTest {

    private WasmPolicyEngine engine;
    private SidecarConfig config;
    private ObjectMapper objectMapper;
    private SidecarConfig.OpaConfig opaConfig;
    private SidecarConfig.OpaConfig.EmbeddedOpaConfig embeddedOpaConfig;

    @BeforeEach
    void setup() throws Exception {
        engine = new WasmPolicyEngine();

        config = mock(SidecarConfig.class);
        objectMapper = mock(ObjectMapper.class);
        opaConfig = mock(SidecarConfig.OpaConfig.class);
        embeddedOpaConfig = mock(SidecarConfig.OpaConfig.EmbeddedOpaConfig.class);

        when(config.opa()).thenReturn(opaConfig);
        when(opaConfig.embedded()).thenReturn(embeddedOpaConfig);

        setField(engine, "config", config);
        setField(engine, "objectMapper", objectMapper);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testInit_NotEmbedded() {
        when(opaConfig.enabled()).thenReturn(true);
        when(opaConfig.mode()).thenReturn("external");
        engine.init(); // Should return quickly without starting watcher
    }

    @Test
    void testInit_Disabled() throws Exception {
        when(opaConfig.enabled()).thenReturn(false);
        engine.init();
        assertNull(getField(engine, "watcherThread"));
    }

    @Test
    void testInit_ExternalMode() throws Exception {
        when(opaConfig.enabled()).thenReturn(true);
        when(opaConfig.mode()).thenReturn("external");
        engine.init();
        assertNull(getField(engine, "watcherThread"));
    }

    @Test
    void testInit_Embedded_StartsWatcher() throws Exception {
        when(opaConfig.enabled()).thenReturn(true);
        when(opaConfig.mode()).thenReturn("embedded");
        when(embeddedOpaConfig.wasmPath()).thenReturn("invalid_path.wasm"); // Will log error but start thread
        engine.init();
        Thread watcher = (Thread) getField(engine, "watcherThread");
        assertNotNull(watcher);
        assertTrue(watcher.isAlive());
        engine.shutdown(); // clean up
    }

    @Test
    void testShutdown() throws Exception {
        when(opaConfig.enabled()).thenReturn(true);
        when(opaConfig.mode()).thenReturn("embedded");
        when(embeddedOpaConfig.wasmPath()).thenReturn("invalid_path.wasm");
        engine.init();

        Thread watcher = (Thread) getField(engine, "watcherThread");
        assertTrue(watcher.isAlive());
        boolean watching = (boolean) getField(engine, "watching");
        assertTrue(watching);

        engine.shutdown();

        watching = (boolean) getField(engine, "watching");
        assertFalse(watching);
    }

    @Test
    void testLoadWasmModule_ClasspathNotFound() {
        when(embeddedOpaConfig.wasmPath()).thenReturn("classpath:policies/doesnotexist.wasm");
        engine.loadWasmModule();
        // Should catch IOException and reset policy ref (stays null)
        assertEquals("WASM module not initialized",
                engine.evaluateEmbeddedWasm(mock(PolicyInput.class)).await().indefinitely().reason());
    }

    @Test
    void testLoadWasmModule_ClasspathWithSlash() {
        when(embeddedOpaConfig.wasmPath()).thenReturn("classpath:/non-existent-slash.wasm");
        engine.loadWasmModule();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testLoadWasmModule_RegularFileSuccess() {
        when(embeddedOpaConfig.wasmPath()).thenReturn("src/main/resources/policies/authz.wasm");

        OpaPolicy.Builder mockBuilder = mock(OpaPolicy.Builder.class);
        OpaPolicy mockPolicy = mock(OpaPolicy.class);

        try (MockedStatic<OpaPolicy> staticPolicy = mockStatic(OpaPolicy.class)) {
            staticPolicy.when(OpaPolicy::builder).thenReturn(mockBuilder);
            when(mockBuilder.withPolicy(any(byte[].class))).thenReturn(mockBuilder);
            when(mockBuilder.build()).thenReturn(mockPolicy);

            engine.loadWasmModule();

            AtomicReference<OpaPolicy> ref = (AtomicReference<OpaPolicy>) getField(engine, "wasmPolicyRef");
            assertNotNull(ref.get());
            assertEquals(mockPolicy, ref.get());
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    @Test
    void testResolveWatchDir_Exists() {
        assertNotNull(engine.resolveWatchDir());
    }

    @Test
    void testStartHotReloadWatcher_NoDir() {
        WasmPolicyEngine engineSpy = spy(engine);
        doReturn(null).when(engineSpy).resolveWatchDir();
        engineSpy.startHotReloadWatcher();
        // Should log and return without error
    }

    @Test
    void testRecompileWasm_ExercisesLogic() throws Exception {
        // This exercises the path mapping logic
        when(embeddedOpaConfig.wasmPath()).thenReturn("classpath:policies/authz.wasm");
        engine.recompileWasm(java.nio.file.Paths.get("src/main/resources/policies"));
        // and for absolute path
        when(embeddedOpaConfig.wasmPath()).thenReturn("/tmp/authz.wasm");
        engine.recompileWasm(java.nio.file.Paths.get("src/main/resources/policies"));
    }

    @Test
    void testEvaluateEmbeddedWasm_Success_NonArray() throws Exception {
        OpaPolicy mockPolicy = mock(OpaPolicy.class);
        AtomicReference<OpaPolicy> ref = new AtomicReference<>(mockPolicy);
        setField(engine, "wasmPolicyRef", ref);

        PolicyInput dummyInput = mock(PolicyInput.class);
        when(objectMapper.writeValueAsString(dummyInput)).thenReturn("{}");
        when(mockPolicy.evaluate("{}")).thenReturn("{\"allow\": true}");

        ObjectMapper realMapper = new ObjectMapper();
        JsonNode allowNode = realMapper.readTree("{\"allow\": true}");
        when(objectMapper.readTree(anyString())).thenReturn(allowNode);

        PolicyDecision decision = engine.evaluateEmbeddedWasm(dummyInput).await().indefinitely();
        assertTrue(decision.allowed());
    }

    @Test
    void testEvaluateEmbeddedWasm_EmptyArray() throws Exception {
        OpaPolicy mockPolicy = mock(OpaPolicy.class);
        AtomicReference<OpaPolicy> ref = new AtomicReference<>(mockPolicy);
        setField(engine, "wasmPolicyRef", ref);

        PolicyInput dummyInput = mock(PolicyInput.class);
        when(objectMapper.writeValueAsString(dummyInput)).thenReturn("{}");
        when(mockPolicy.evaluate("{}")).thenReturn("[]");

        ObjectMapper realMapper = new ObjectMapper();
        JsonNode emptyArray = realMapper.readTree("[]");
        when(objectMapper.readTree(anyString())).thenReturn(emptyArray);

        PolicyDecision decision = engine.evaluateEmbeddedWasm(dummyInput).await().indefinitely();
        assertFalse(decision.allowed());
        assertEquals("Unexpected OPA response format", decision.reason());
    }

    @Test
    void testEvaluateEmbeddedWasm_Success_Array() throws Exception {
        OpaPolicy mockPolicy = mock(OpaPolicy.class);
        AtomicReference<OpaPolicy> ref = new AtomicReference<>(mockPolicy);
        setField(engine, "wasmPolicyRef", ref);

        PolicyInput dummyInput = mock(PolicyInput.class);
        when(objectMapper.writeValueAsString(dummyInput)).thenReturn("{}");
        when(mockPolicy.evaluate("{}")).thenReturn("[{\"result\": {\"allow\": true}}]");

        ObjectMapper realMapper = new ObjectMapper();
        JsonNode arrayNode = realMapper.readTree("[{\"result\": {\"allow\": true}}]");
        when(objectMapper.readTree(anyString())).thenReturn(arrayNode);

        PolicyDecision decision = engine.evaluateEmbeddedWasm(dummyInput).await().indefinitely();
        assertTrue(decision.allowed());
    }

    @Test
    void testEvaluateEmbeddedWasm_Exception() throws Exception {
        OpaPolicy mockPolicy = mock(OpaPolicy.class);
        AtomicReference<OpaPolicy> ref = new AtomicReference<>(mockPolicy);
        setField(engine, "wasmPolicyRef", ref);

        PolicyInput dummyInput = mock(PolicyInput.class);
        when(objectMapper.writeValueAsString(dummyInput)).thenThrow(new RuntimeException("JSON format error"));

        Exception exception = null;
        try {
            engine.evaluateEmbeddedWasm(dummyInput).await().indefinitely();
        } catch (Exception e) {
            exception = e;
        }
        assertNotNull(exception);
        assertTrue(exception.getMessage().contains("WASM evaluation failed"));
    }

    @Test
    void testEvaluateEmbeddedWasm_NoPolicy() {
        PolicyInput dummyInput = mock(PolicyInput.class);
        PolicyDecision decision = engine.evaluateEmbeddedWasm(dummyInput).await().indefinitely();
        assertFalse(decision.allowed());
        assertEquals("WASM module not initialized", decision.reason());
    }

    private Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
