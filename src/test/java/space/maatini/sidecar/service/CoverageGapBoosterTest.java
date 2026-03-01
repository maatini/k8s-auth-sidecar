package space.maatini.sidecar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.model.PolicyDecision;
import space.maatini.sidecar.model.PolicyInput;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CoverageGapBoosterTest {

    PolicyService policyService;
    SidecarConfig config;
    WebClient webClient;
    WasmPolicyEngine wasmEngine;
    ObjectMapper objectMapper;

    @BeforeEach
    void setup() throws Exception {
        policyService = new PolicyService();

        config = mock(SidecarConfig.class);
        webClient = mock(WebClient.class);
        wasmEngine = mock(WasmPolicyEngine.class);
        objectMapper = new ObjectMapper(); // use real object mapper for json building

        setField(policyService, "config", config);
        setField(policyService, "webClient", webClient);
        setField(policyService, "wasmEngine", wasmEngine);
        setField(policyService, "objectMapper", objectMapper);
        setField(policyService, "self", policyService);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field " + name + " not found in hierarchy of " + target.getClass());
    }

    @Test
    void testPolicyService_Gaps() {
        SidecarConfig.OpaConfig opaConfig = mock(SidecarConfig.OpaConfig.class);
        when(config.opa()).thenReturn(opaConfig);
        when(opaConfig.enabled()).thenReturn(true);
        when(opaConfig.mode()).thenReturn("embedded");

        when(wasmEngine.evaluateEmbeddedWasm(any())).thenReturn(Uni.createFrom().item(PolicyDecision.allow()));

        AuthContext auth = AuthContext.builder().userId("gap-u1").build();
        PolicyDecision d = policyService.evaluate(auth, "GET", "/gap-1", Map.of(), Map.of()).await().indefinitely();
        assertTrue(d.allowed());

        // unknown mode (should trigger exception locally, but let's test fallback
        // method directly)
        when(opaConfig.mode()).thenReturn("unknown");
        try {
            policyService.evaluate(auth, "GET", "/gap-2", Map.of(), Map.of()).await().indefinitely();
            fail("Expected exception due to missing config mock external()");
        } catch (Exception e) {
            // manually trigger fallback since we are bypassing interceptors
            PolicyInput input = PolicyInput.from(auth, "GET", "/gap-2", Map.of(), Map.of());
            d = policyService.fallbackEvaluateExternal(input, e).await().indefinitely();
        }
        assertFalse(d.allowed());
        assertEquals("Policy subsystem unavailable. Access denied for security.", d.reason());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPolicyService_ExternalGaps() {
        SidecarConfig.OpaConfig opaConfig = mock(SidecarConfig.OpaConfig.class);
        SidecarConfig.OpaConfig.ExternalOpaConfig extConfig = mock(SidecarConfig.OpaConfig.ExternalOpaConfig.class);
        when(config.opa()).thenReturn(opaConfig);
        when(opaConfig.enabled()).thenReturn(true);
        when(opaConfig.mode()).thenReturn("external");
        when(opaConfig.external()).thenReturn(extConfig);
        when(extConfig.url()).thenReturn("http://opa");
        when(extConfig.decisionPath()).thenReturn("/v1/data/authz/allow");
        when(opaConfig.defaultPackage()).thenReturn("authz");
        when(opaConfig.defaultRule()).thenReturn("allow");

        HttpRequest<Buffer> mockReq = mock(HttpRequest.class);
        HttpResponse<Buffer> mockResp = mock(HttpResponse.class);

        when(webClient.postAbs(anyString())).thenReturn(mockReq);
        when(mockReq.timeout(anyLong())).thenReturn(mockReq);
        when(mockReq.sendJson(any())).thenReturn(Uni.createFrom().item(mockResp));

        // 143: OPA error response (non-200)
        when(mockResp.statusCode()).thenReturn(500);
        when(mockResp.bodyAsString()).thenReturn("Internal error");

        AuthContext auth = AuthContext.builder().userId("gap-u2").build();
        PolicyDecision d = policyService.evaluate(auth, "GET", "/gap-3", Map.of(), Map.of()).await().indefinitely();
        assertFalse(d.allowed());

        // 156: OPA null result
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.bodyAsString()).thenReturn("{\"result\": null}");
        d = policyService.evaluate(auth, "GET", "/gap-null", Map.of(), Map.of()).await().indefinitely();
        assertFalse(d.allowed());
        assertEquals("No result from OPA", d.reason());

        // 162-164: Exception handling
        when(mockReq.sendJson(any())).thenReturn(Uni.createFrom().failure(new RuntimeException("Network fail")));
        try {
            policyService.evaluate(auth, "GET", "/gap-4", Map.of(), Map.of()).await().indefinitely();
        } catch (Exception e) {
            PolicyInput input = PolicyInput.from(auth, "GET", "/gap-4", Map.of(), Map.of());
            d = policyService.fallbackEvaluateExternal(input, e).await().indefinitely();
        }
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("unavailable")); // Interceptor behavior
    }

    @Test
    void testRolesService_Disabled() throws Exception {
        RolesService rolesService = new RolesService();
        SidecarConfig mockConfig = mock(SidecarConfig.class);
        SidecarConfig.AuthzConfig authz = mock(SidecarConfig.AuthzConfig.class);
        SidecarConfig.AuthzConfig.RolesServiceConfig rsConfig = mock(
                SidecarConfig.AuthzConfig.RolesServiceConfig.class);

        when(mockConfig.authz()).thenReturn(authz);
        when(authz.rolesService()).thenReturn(rsConfig);
        when(rsConfig.enabled()).thenReturn(false);

        setField(rolesService, "config", mockConfig);

        AuthContext auth = AuthContext.builder().userId("u").build();
        AuthContext result = rolesService.enrichWithRoles(auth).await().indefinitely();
        assertSame(auth, result);
    }
}
