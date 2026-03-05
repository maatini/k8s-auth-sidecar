package space.maatini.sidecar.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.common.config.SidecarConfig;
import space.maatini.sidecar.common.model.AuthContext;
import space.maatini.sidecar.common.model.PolicyDecision;
import space.maatini.sidecar.common.model.PolicyInput;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PolicyServicePojoTest {

    private PolicyService policyService;
    private SidecarConfig config;
    private ObjectMapper objectMapper;
    private WasmPolicyEngine wasmEngine;

    @BeforeEach
    void setup() throws Exception {
        policyService = new PolicyService();
        config = mock(SidecarConfig.class);
        objectMapper = new ObjectMapper();
        wasmEngine = mock(WasmPolicyEngine.class);

        setField(policyService, "config", config);
        setField(policyService, "objectMapper", objectMapper);
        setField(policyService, "wasmEngine", wasmEngine);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testEvaluate_Disabled() {
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
        when(config.opa()).thenReturn(opa);
        when(opa.enabled()).thenReturn(false);

        PolicyDecision result = policyService.evaluate(AuthContext.anonymous(), "GET", "/", null, null).await()
                .indefinitely();
        assertTrue(result.allowed());
    }

    @Test
    void testEvaluate_Embedded_Success() {
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
        when(config.opa()).thenReturn(opa);
        when(opa.enabled()).thenReturn(true);

        PolicyDecision decision = PolicyDecision.allow();
        when(wasmEngine.evaluateEmbeddedWasm(any())).thenReturn(Uni.createFrom().item(decision));

        PolicyDecision result = policyService.evaluate(AuthContext.anonymous(), "GET", "/", null, null).await()
                .indefinitely();
        assertTrue(result.allowed());
        verify(wasmEngine).evaluateEmbeddedWasm(any());
    }

    @Test
    void testEvaluate_Embedded_Failure() {
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
        when(config.opa()).thenReturn(opa);
        when(opa.enabled()).thenReturn(true);

        when(wasmEngine.evaluateEmbeddedWasm(any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("WASM crash")));

        PolicyDecision result = policyService.evaluate(AuthContext.anonymous(), "GET", "/", null, null).await()
                .indefinitely();
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("failed"));
    }

    @Test
    void testParsePolicyResult() throws Exception {
        // Boolean cases
        assertTrue(PolicyService.parsePolicyResult(objectMapper.valueToTree(true)).allowed());
        assertFalse(PolicyService.parsePolicyResult(objectMapper.valueToTree(false)).allowed());

        // Object cases - Allow
        assertTrue(PolicyService.parsePolicyResult(objectMapper.readTree("{\"allow\": true}")).allowed());

        // Object cases - Deny with reason
        PolicyDecision d1 = PolicyService
                .parsePolicyResult(objectMapper.readTree("{\"allow\": false, \"reason\": \"Too early\"}"));
        assertFalse(d1.allowed());
        assertEquals("Too early", d1.reason());

        // Object cases - Deny with violations
        PolicyDecision d2 = PolicyService
                .parsePolicyResult(objectMapper.readTree("{\"allow\": false, \"violations\": [\"v1\", \"v2\"]}"));
        assertFalse(d2.allowed());
        assertEquals(2, d2.violations().size());
        assertEquals("v1", d2.firstViolation().get());

        // Null/Missing
        assertFalse(PolicyService.parsePolicyResult(null).allowed());
        assertFalse(PolicyService.parsePolicyResult(objectMapper.readTree("{\"something\": \"else\"}")).allowed());
    }

    @Test
    void testParsePolicyResult_GenericDeny() throws Exception {
        // Missing "allow" field in object
        assertFalse(PolicyService.parsePolicyResult(objectMapper.readTree("{\"reason\": \"Just because\"}")).allowed());
    }

    @Test
    void testParsePolicyResult_MissingReasonAndViolations() throws Exception {
        // allow: false but NO reason and NO violations array
        PolicyDecision d = PolicyService.parsePolicyResult(objectMapper.readTree("{\"allow\": false}"));
        assertFalse(d.allowed());
        assertEquals("Access denied by policy", d.reason());
        assertTrue(d.violations().isEmpty());
    }
}
