package space.maatini.sidecar.infrastructure.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.styra.opa.wasm.OpaPolicy;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.domain.model.PolicyDecision;
import space.maatini.sidecar.domain.model.PolicyInput;

import java.lang.reflect.Constructor;
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
        when(embeddedConfig.wasmPath()).thenReturn("classpath:policies/policy.wasm");
        when(embeddedConfig.poolSize()).thenReturn(10);
        when(embeddedConfig.poolAcquireTimeoutMs()).thenReturn(50);

        objectMapper = new ObjectMapper();

        Field configField = WasmPolicyEngine.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(engine, config);

        Field mapperField = WasmPolicyEngine.class.getDeclaredField("objectMapper");
        mapperField.setAccessible(true);
        mapperField.set(engine, objectMapper);

        // Initialize pool manually since @PostConstruct is not called
        Field poolField = WasmPolicyEngine.class.getDeclaredField("policyPoolRef");
        poolField.setAccessible(true);
        AtomicReference<java.util.concurrent.ArrayBlockingQueue<OpaPolicy>> poolRef =
                (AtomicReference<java.util.concurrent.ArrayBlockingQueue<OpaPolicy>>) poolField.get(engine);
        poolRef.set(new java.util.concurrent.ArrayBlockingQueue<>(10));

        mockPolicy = mock(OpaPolicy.class);
    }

    @Test
    void testEvaluateEmbeddedWasm_ArrayResult() throws Exception {
        setWasmBundle(engine, new byte[0], 1L);
        injectMockPolicy(engine, mockPolicy, 1L);

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
        setWasmBundle(engine, new byte[0], 1L);
        injectMockPolicy(engine, mockPolicy, 1L);

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
    void testResolvePath_Classpath() throws Exception {
        engine.resolvePath("classpath:policies/authz.rego");
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
    void testLoadWasmModule_FileNotFound() throws Exception {
        when(embeddedConfig.wasmPath()).thenReturn("/invalid/test/path.wasm");
        engine.loadWasmModule();
        assertFalse(engine.isModuleLoaded());
    }

    @Test
    void testEvaluateEmbeddedWasm_NullResult() throws Exception {
        setWasmBundle(engine, new byte[0], 1L);
        injectMockPolicy(engine, mockPolicy, 1L);

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
        setWasmBundle(engine, new byte[0], 1L);
        injectMockPolicy(engine, mockPolicy, 1L);

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

        engine.loadWasmModule();
        assertFalse(engine.isModuleLoaded());
    }

    @Test
    void testEvaluateEmbeddedWasm_EmptyArray() throws Exception {
        setWasmBundle(engine, new byte[0], 1L);
        injectMockPolicy(engine, mockPolicy, 1L);

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
        Path p = engine.resolvePath("src/main/resources/policies/authz.rego");
        assertNotNull(p);
        assertTrue(Files.exists(p));
    }




    @Test
    void testIsModuleLoaded() {
        assertFalse(engine.isModuleLoaded());
    }


    @Test
    void testResolveWatchDir_Invalid() throws Exception {
        Method m = WasmPolicyEngine.class.getDeclaredMethod("resolveWatchDir");
        m.setAccessible(true);
        m.invoke(engine);
    }

    @Test
    void testResolvePath_Classpath_Survivor() throws Exception {
        Method m = WasmPolicyEngine.class.getDeclaredMethod("resolvePath", String.class);
        m.setAccessible(true);
        Path p = (Path) m.invoke(engine, "classpath:policies/policy.wasm");
        assertNotNull(p);
        assertTrue(p.toString().endsWith("policy.wasm"));
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



    private void setWasmBundle(WasmPolicyEngine engine, byte[] bytes, long version) throws Exception {
        Class<?> bundleClass = Class.forName("space.maatini.sidecar.infrastructure.policy.WasmPolicyEngine$PolicyBundle");
        Constructor<?> constructor = bundleClass.getDeclaredConstructor(byte[].class, long.class);
        constructor.setAccessible(true);
        Object bundle = constructor.newInstance(bytes, version);

        Field bundleRefField = WasmPolicyEngine.class.getDeclaredField("wasmBundleRef");
        bundleRefField.setAccessible(true);
        AtomicReference<Object> ref = (AtomicReference<Object>) bundleRefField.get(engine);
        ref.set(bundle);
    }

    private void injectMockPolicy(WasmPolicyEngine engine, OpaPolicy policy, long version) throws Exception {
        Field policyPoolField = WasmPolicyEngine.class.getDeclaredField("policyPoolRef");
        policyPoolField.setAccessible(true);
        AtomicReference<java.util.concurrent.ArrayBlockingQueue<OpaPolicy>> poolRef =
                (AtomicReference<java.util.concurrent.ArrayBlockingQueue<OpaPolicy>>) policyPoolField.get(engine);
        java.util.concurrent.ArrayBlockingQueue<OpaPolicy> pool = poolRef.get();
        pool.clear();
        pool.offer(policy);
    }
}
