package space.maatini.sidecar.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.model.PolicyDecision;
import space.maatini.sidecar.model.PolicyInput;

import java.lang.reflect.Field;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PolicyServicePojoTest {

    private PolicyService policyService;
    private SidecarConfig config;
    private Vertx vertx;
    private ObjectMapper objectMapper;
    private WasmPolicyEngine wasmEngine;
    private WebClient webClient;

    @BeforeEach
    void setup() throws Exception {
        policyService = new PolicyService();
        config = mock(SidecarConfig.class);
        vertx = mock(Vertx.class);
        objectMapper = new ObjectMapper();
        wasmEngine = mock(WasmPolicyEngine.class);
        webClient = mock(WebClient.class);

        setField(policyService, "config", config);
        setField(policyService, "vertx", vertx);
        setField(policyService, "objectMapper", objectMapper);
        setField(policyService, "wasmEngine", wasmEngine);
        setField(policyService, "webClient", webClient);
        setField(policyService, "self", policyService);
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
    void testEvaluate_Embedded() {
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
        when(config.opa()).thenReturn(opa);
        when(opa.enabled()).thenReturn(true);
        when(opa.mode()).thenReturn("embedded");

        PolicyDecision decision = PolicyDecision.allow();
        when(wasmEngine.evaluateEmbeddedWasm(any())).thenReturn(Uni.createFrom().item(decision));

        PolicyDecision result = policyService.evaluate(AuthContext.anonymous(), "GET", "/", null, null).await()
                .indefinitely();
        assertTrue(result.allowed());
        verify(wasmEngine).evaluateEmbeddedWasm(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testEvaluate_External_Success() {
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
        SidecarConfig.OpaConfig.ExternalOpaConfig external = mock(SidecarConfig.OpaConfig.ExternalOpaConfig.class);
        when(config.opa()).thenReturn(opa);
        when(opa.enabled()).thenReturn(true);
        when(opa.mode()).thenReturn("external");
        when(opa.external()).thenReturn(external);
        when(external.url()).thenReturn("http://opa");
        when(external.decisionPath()).thenReturn("/v1/data/policy");

        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.postAbs(anyString())).thenReturn(request);

        HttpResponse<Buffer> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.bodyAsString()).thenReturn("{\"result\": {\"allow\": true}}");

        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(resp));

        PolicyDecision result = policyService.evaluate(AuthContext.anonymous(), "GET", "/", null, null).await()
                .indefinitely();
        assertTrue(result.allowed());
    }

    @Test
    void testParsePolicyResult_StateTable() throws Exception {
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
    void testFallbackEvaluateExternal() {
        PolicyInput input = PolicyInput.from(AuthContext.anonymous(), "GET", "/test", null, null);
        PolicyDecision result = policyService.fallbackEvaluateExternal(input, new RuntimeException("Fail")).await()
                .indefinitely();
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("unavailable"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testEvaluate_External_HttpError() {
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
        SidecarConfig.OpaConfig.ExternalOpaConfig external = mock(SidecarConfig.OpaConfig.ExternalOpaConfig.class);
        when(config.opa()).thenReturn(opa);
        when(opa.enabled()).thenReturn(true);
        when(opa.mode()).thenReturn("external");
        when(opa.external()).thenReturn(external);
        when(external.url()).thenReturn("http://opa");
        when(external.decisionPath()).thenReturn("/v1/data/policy");

        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.postAbs(anyString())).thenReturn(request);

        HttpResponse<Buffer> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(500);

        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(resp));

        PolicyDecision result = policyService.evaluate(AuthContext.anonymous(), "GET", "/", null, null).await()
                .indefinitely();
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("500"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testEvaluate_External_JsonError() {
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
        SidecarConfig.OpaConfig.ExternalOpaConfig external = mock(SidecarConfig.OpaConfig.ExternalOpaConfig.class);
        when(config.opa()).thenReturn(opa);
        when(opa.enabled()).thenReturn(true);
        when(opa.mode()).thenReturn("external");
        when(opa.external()).thenReturn(external);
        when(external.url()).thenReturn("http://opa");
        when(external.decisionPath()).thenReturn("/v1/data/policy");

        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.postAbs(anyString())).thenReturn(request);

        HttpResponse<Buffer> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.bodyAsString()).thenReturn("INVALID_JSON");

        when(request.sendJson(any())).thenReturn(Uni.createFrom().item(resp));

        PolicyDecision result = policyService.evaluate(AuthContext.anonymous(), "GET", "/", null, null).await()
                .indefinitely();
        assertFalse(result.allowed());
        assertTrue(result.reason().contains("Failed to parse OPA response"));
    }

    @Test
    void testParsePolicyResult_GenericDeny() throws Exception {
        // Missing "allow" field in object
        assertFalse(PolicyService.parsePolicyResult(objectMapper.readTree("{\"reason\": \"Just because\"}")).allowed());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testEvaluate_External_LogFailure() {
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
        SidecarConfig.OpaConfig.ExternalOpaConfig external = mock(SidecarConfig.OpaConfig.ExternalOpaConfig.class);
        when(config.opa()).thenReturn(opa);
        when(opa.enabled()).thenReturn(true);
        when(opa.mode()).thenReturn("external");
        when(opa.external()).thenReturn(external);
        when(external.url()).thenReturn("http://opa");
        when(external.decisionPath()).thenReturn("/v1/data/policy");

        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.postAbs(anyString())).thenReturn(request);

        // Explicitly fail the Uni to trigger onFailure().invoke()
        when(request.sendJson(any())).thenReturn(Uni.createFrom().failure(new RuntimeException("Connection Refused")));

        assertThrows(Exception.class, () -> {
            policyService.evaluate(AuthContext.anonymous(), "GET", "/", null, null).await().indefinitely();
        });
    }

    @Test
    void testParsePolicyResult_MissingReasonAndViolations() throws Exception {
        // allow: false but NO reason and NO violations array
        PolicyDecision d = PolicyService.parsePolicyResult(objectMapper.readTree("{\"allow\": false}"));
        assertFalse(d.allowed());
        assertEquals("Access denied by policy", d.reason());
        assertTrue(d.violations().isEmpty());
    }

    @Test
    void testParsePolicyResult_MalformedViolations() throws Exception {
        // violations is NOT an array
        PolicyDecision d = PolicyService
                .parsePolicyResult(objectMapper.readTree("{\"allow\": false, \"violations\": \"not-an-array\"}"));
        assertFalse(d.allowed());
        assertTrue(d.violations().isEmpty());
    }

    @Test
    void testParsePolicyResult_NullLeaf() throws Exception {
        // result is a JsonNode that is null (literal null in JSON)
        assertFalse(PolicyService.parsePolicyResult(objectMapper.readTree("null")).allowed());
    }

    @Test
    void testParsePolicyResult_JavaNull() {
        // Explicitly calling with Java null to kill (result == null) mutant
        PolicyDecision d = PolicyService.parsePolicyResult(null);
        assertFalse(d.allowed());
        assertEquals("No result from OPA", d.reason());
    }

    @Test
    void testParsePolicyResult_ViolationsNotArray() throws Exception {
        // Violations is a string, not an array
        String json = "{\"allow\": true, \"violations\": \"not-an-array\"}";
        PolicyDecision d = PolicyService.parsePolicyResult(objectMapper.readTree(json));
        assertTrue(d.allowed());
        assertTrue(d.violations().isEmpty());
    }
}
