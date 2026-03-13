package space.maatini.sidecar.application.service;
import space.maatini.sidecar.infrastructure.policy.WasmPolicyEngine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.domain.model.PolicyDecision;
import space.maatini.sidecar.domain.model.PolicyInput;
import space.maatini.sidecar.domain.model.PolicyCacheKey;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolicyServiceExtTest {

    @Test
    void testParsePolicyResult_AllObjectBranches() throws Exception {
        Method parseMethod = PolicyService.class.getDeclaredMethod("parsePolicyResult",
                com.fasterxml.jackson.databind.JsonNode.class);
        parseMethod.setAccessible(true);
        ObjectMapper mapper = new ObjectMapper();

        // Null result
        PolicyDecision r1 = (PolicyDecision) parseMethod.invoke(null, (Object) null);
        assertFalse(r1.allowed());

        // Boolean true
        PolicyDecision r2 = (PolicyDecision) parseMethod.invoke(null, mapper.getNodeFactory().booleanNode(true));
        assertTrue(r2.allowed());

        // Boolean false
        PolicyDecision r3 = (PolicyDecision) parseMethod.invoke(null, mapper.getNodeFactory().booleanNode(false));
        assertFalse(r3.allowed());

        // Object missing "allow" field
        ObjectNode missingAllow = mapper.createObjectNode();
        missingAllow.put("other", true);
        PolicyDecision r4 = (PolicyDecision) parseMethod.invoke(null, missingAllow);
        assertFalse(r4.allowed()); // Will fall through to "Unexpected OPA response format"

        // Object with allow=false and violations
        ObjectNode denied = mapper.createObjectNode();
        denied.put("allow", false);
        denied.put("reason", "Custom Deny");
        ArrayNode violations = denied.putArray("violations");
        violations.add("violation1");
        PolicyDecision r5 = (PolicyDecision) parseMethod.invoke(null, denied);
        assertFalse(r5.allowed());
        assertEquals("Custom Deny", r5.reason());
        assertTrue(r5.violations().contains("violation1"));
    }

    @Test
    void testEvaluate_DisabledAndEnabledOpa() {
        PolicyService service = new PolicyService();
        service.config = mock(SidecarConfig.class);
        SidecarConfig.OpaConfig opaConfig = mock(SidecarConfig.OpaConfig.class);
        when(service.config.opa()).thenReturn(opaConfig);

        // Disabled
        when(opaConfig.enabled()).thenReturn(false);
        Uni<PolicyDecision> decisionUni = service.evaluate(AuthContext.anonymous(), "GET", "/api", Map.of(), Map.of());
        PolicyDecision d1 = decisionUni.await().indefinitely();
        assertTrue(d1.allowed());

        // Enabled
        when(opaConfig.enabled()).thenReturn(true);
        service.wasmEngine = mock(WasmPolicyEngine.class);
        when(service.wasmEngine.evaluateEmbeddedWasm(any(PolicyInput.class)))
                .thenReturn(Uni.createFrom().item(PolicyDecision.allow()));

        Uni<PolicyDecision> decisionUni2 = service.evaluate(AuthContext.anonymous(), "GET", "/api", Map.of(), Map.of());
        PolicyDecision d2 = decisionUni2.await().indefinitely();
        assertTrue(d2.allowed());
    }

    @Test
    void testEvaluatePolicy_FailureFallback() {
        PolicyService service = new PolicyService();
        service.wasmEngine = mock(WasmPolicyEngine.class);

        when(service.wasmEngine.evaluateEmbeddedWasm(any(PolicyInput.class)))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Crash!")));

        PolicyInput input = new PolicyInput(
                new PolicyInput.RequestInfo("GET", "/api", java.util.Map.of(), java.util.Map.of()),
                new PolicyInput.UserInfo("user1", "test@user.com", java.util.Set.of(), java.util.Set.of()),
                new PolicyInput.ResourceInfo("api", null, null),
                java.util.Map.of());

        PolicyCacheKey key = new PolicyCacheKey(
                input.user().id(),
                input.user().roles(),
                input.request().method(),
                input.request().path()
        );
        Uni<PolicyDecision> decisionUni = service.evaluatePolicy(key, input);
        PolicyDecision d = decisionUni.await().indefinitely(); // Will be evaluated through recoverWithItem
        assertFalse(d.allowed());
        assertTrue(d.reason().contains("Policy evaluation failed"));
    }
}
