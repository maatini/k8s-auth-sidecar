package space.maatini.sidecar.infrastructure.policy;
import space.maatini.sidecar.infrastructure.policy.WasmPolicyEngine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.styra.opa.wasm.OpaPolicy;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.domain.model.PolicyDecision;
import space.maatini.sidecar.domain.model.PolicyInput;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
    void testResolveWatchDir_Default() {
        Path p = engine.resolveWatchDir();
        assertNotNull(p);
        assertTrue(Files.isDirectory(p));
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

    @Test
    void testEvaluateEmbeddedWasm_NullResult() throws Exception {
        Field refField = WasmPolicyEngine.class.getDeclaredField("wasmPolicyRef");
        refField.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<OpaPolicy> ref = (AtomicReference<OpaPolicy>) refField.get(engine);
        ref.set(mockPolicy);

        when(mockPolicy.evaluate(anyString())).thenReturn(null);

        PolicyInput input = new PolicyInput(
                new PolicyInput.RequestInfo("GET", "/api", java.util.Map.of(), java.util.Map.of()),
                new PolicyInput.UserInfo("u1", "e1", java.util.Set.of(), java.util.Set.of()),
                new PolicyInput.ResourceInfo("api", null, null),
                java.util.Map.of());

        PolicyDecision decision = engine.evaluateEmbeddedWasm(input).await().indefinitely();
        assertFalse(decision.allowed());
        assertTrue(decision.reason().contains("null result"));
    }

    @Test
    void testEvaluateEmbeddedWasm_MalformedJson() throws Exception {
        Field refField = WasmPolicyEngine.class.getDeclaredField("wasmPolicyRef");
        refField.setAccessible(true);
        AtomicReference<OpaPolicy> ref = (AtomicReference<OpaPolicy>) refField.get(engine);
        ref.set(mockPolicy);

        when(mockPolicy.evaluate(anyString())).thenReturn("not-json");

        PolicyInput input = new PolicyInput(
                new PolicyInput.RequestInfo("GET", "/api", java.util.Map.of(), java.util.Map.of()),
                new PolicyInput.UserInfo("u1", "e1", java.util.Set.of(), java.util.Set.of()),
                new PolicyInput.ResourceInfo("api", null, null),
                java.util.Map.of());

        PolicyDecision decision = engine.evaluateEmbeddedWasm(input).await().indefinitely();
        assertFalse(decision.allowed());
    }

    @Test
    void testInit_OpaDisabled() throws Exception {
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
        when(config.opa()).thenReturn(opa);
        when(opa.enabled()).thenReturn(false);

        // Should not call loadWasmModule
        engine.init();
        assertFalse(engine.isModuleLoaded());
    }

    @Test
    void testLoadWasmModule_InvalidClasspath() {
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
        SidecarConfig.OpaConfig.EmbeddedOpaConfig embedded = mock(SidecarConfig.OpaConfig.EmbeddedOpaConfig.class);
        when(config.opa()).thenReturn(opa);
        when(opa.embedded()).thenReturn(embedded);
        when(embedded.wasmPath()).thenReturn("classpath:non-existent.wasm");

        // Should handle failure gracefully (not load module)
        engine.loadWasmModule();
        assertFalse(engine.isModuleLoaded());
    }

    @Test
    void testEvaluateEmbeddedWasm_EmptyArray() throws Exception {
        Field refField = WasmPolicyEngine.class.getDeclaredField("wasmPolicyRef");
        refField.setAccessible(true);
        @SuppressWarnings("unchecked")
        AtomicReference<OpaPolicy> ref = (AtomicReference<OpaPolicy>) refField.get(engine);
        ref.set(mockPolicy);

        when(mockPolicy.evaluate(anyString())).thenReturn("[]");

        PolicyInput input = new PolicyInput(
                new PolicyInput.RequestInfo("GET", "/api", java.util.Map.of(), java.util.Map.of()),
                new PolicyInput.UserInfo("u1", "e1", java.util.Set.of(), java.util.Set.of()),
                new PolicyInput.ResourceInfo("api", null, null),
                java.util.Map.of());

        PolicyDecision decision = engine.evaluateEmbeddedWasm(input).await().indefinitely();
        assertFalse(decision.allowed());
    }

    @Test
    void testResolvePath_Local() {
        Path p = engine.resolvePath("src/main/resources/policies/authz.wasm");
        assertNotNull(p);
        assertTrue(Files.exists(p));
    }

    @Test
    void testShutdown() throws Exception {
        // Mock a thread to ensure it's interrupted
        Thread mockThread = mock(Thread.class);
        Field watcherField = WasmPolicyEngine.class.getDeclaredField("watcherThread");
        watcherField.setAccessible(true);
        watcherField.set(engine, mockThread);

        engine.shutdown();
        verify(mockThread).interrupt();

        Field watchingField = WasmPolicyEngine.class.getDeclaredField("watching");
        watchingField.setAccessible(true);
        assertFalse((Boolean) watchingField.get(engine));
    }

    @Test
    void testRecompileWasm_OpaMissing() {
        engine.recompileWasm(Paths.get("src/main/resources/policies"));
    }

    @Test
    void testIsModuleLoaded() {
        assertFalse(engine.isModuleLoaded());
    }

    @Test
    void testStartHotReloadWatcher_NoDir() {
        engine.startHotReloadWatcher();
    }

    @Test
    void testResolveWatchDir_Invalid() throws Exception {
        Method m = WasmPolicyEngine.class.getDeclaredMethod("resolveWatchDir");
        m.setAccessible(true);
        // This method checks hardcoded paths "src/main/resources/policies" and
        // "/policies"
        // Correctly invoking the zero-argument method.
        Object result = m.invoke(engine);
        // result might be null or Path depending on environment, but we ensure no
        // crash.
    }

    @Test
    void testResolvePath_Classpath_Survivor() throws Exception {
        Method m = WasmPolicyEngine.class.getDeclaredMethod("resolvePath", String.class);
        m.setAccessible(true);
        Path p = (Path) m.invoke(engine, "classpath:policies/authz.wasm");
        assertNotNull(p);
        assertTrue(p.toString().endsWith("authz.wasm"));
    }

    @Test
    void testResolvePath_File_Survivor() throws Exception {
        Method m = WasmPolicyEngine.class.getDeclaredMethod("resolvePath", String.class);
        m.setAccessible(true);
        Files.createDirectories(Paths.get("target/test-ops"));
        Path tempFile = Paths.get("target/test-ops/dummy.wasm");
        Files.writeString(tempFile, "dummy");
        Path p = (Path) m.invoke(engine, tempFile.toAbsolutePath().toString());
        assertNotNull(p);
        assertEquals(tempFile.toAbsolutePath(), p.toAbsolutePath());
    }

    @Test
    void testRecompileWasm_Direct() {
        engine.recompileWasm(Paths.get("src/main/resources/policies"));
    }
}
