package space.maatini.sidecar.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.model.PolicyDecision;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PolicyService.
 */
@QuarkusTest
class PolicyServiceTest {

        @Inject
        PolicyService policyService;

        @Test
        void testEvaluate_SuperadminAllowed() {
                AuthContext authContext = AuthContext.builder()
                                .userId("superadmin-user")
                                .roles(Set.of("superadmin"))
                                .build();

                PolicyDecision decision = policyService.evaluate(
                                authContext, "GET", "/api/admin/settings", Map.of(), Map.of()).await()
                                .atMost(Duration.ofSeconds(5));

                assertTrue(decision.allowed());
        }

        @Test
        void testEvaluate_AdminAccessToAdminPath() {
                AuthContext authContext = AuthContext.builder()
                                .userId("admin-user")
                                .roles(Set.of("admin"))
                                .build();

                PolicyDecision decision = policyService.evaluate(
                                authContext, "GET", "/api/admin/settings", Map.of(), Map.of()).await()
                                .atMost(Duration.ofSeconds(5));

                assertTrue(decision.allowed());
        }

        @Test
        void testEvaluate_UserDeniedAdminPath() {
                AuthContext authContext = AuthContext.builder()
                                .userId("regular-user")
                                .roles(Set.of("user"))
                                .build();

                PolicyDecision decision = policyService.evaluate(
                                authContext, "GET", "/api/admin/settings", Map.of(), Map.of()).await()
                                .atMost(Duration.ofSeconds(5));

                assertFalse(decision.allowed());
                assertNotNull(decision.reason());
        }

        @Test
        void testEvaluate_UserManagerCanReadUsers() {
                AuthContext authContext = AuthContext.builder()
                                .userId("manager-user")
                                .roles(Set.of("user-manager"))
                                .build();

                PolicyDecision decision = policyService.evaluate(
                                authContext, "GET", "/api/users", Map.of(), Map.of()).await()
                                .atMost(Duration.ofSeconds(5));

                assertTrue(decision.allowed());
        }

        @Test
        void testEvaluate_ViewerCannotWrite() {
                AuthContext authContext = AuthContext.builder()
                                .userId("viewer-user")
                                .roles(Set.of("viewer"))
                                .build();

                PolicyDecision decision = policyService.evaluate(
                                authContext, "POST", "/api/users", Map.of(), Map.of()).await()
                                .atMost(Duration.ofSeconds(5));

                assertFalse(decision.allowed());
        }

        @Test
        void testEvaluate_UnauthenticatedDenied() {
                AuthContext authContext = AuthContext.anonymous();

                PolicyDecision decision = policyService.evaluate(
                                authContext, "GET", "/api/users", Map.of(), Map.of()).await()
                                .atMost(Duration.ofSeconds(5));

                assertFalse(decision.allowed());
                assertNotNull(decision.reason());
        }

        @Test
        void testEvaluate_PublicPathAllowed() {
                AuthContext authContext = AuthContext.anonymous();

                PolicyDecision decision = policyService.evaluate(
                                authContext, "GET", "/health", Map.of(), Map.of()).await()
                                .atMost(Duration.ofSeconds(5));

                assertTrue(decision.allowed());
        }

        @Test
        void testEvaluate_ApiPublicPathAllowed() {
                AuthContext authContext = AuthContext.anonymous();

                PolicyDecision decision = policyService.evaluate(
                                authContext, "GET", "/api/public/info", Map.of(), Map.of()).await()
                                .atMost(Duration.ofSeconds(5));

                assertTrue(decision.allowed());
        }

        @Test
        void testEvaluate_UserCanAccessOwnResources() {
                AuthContext authContext = AuthContext.builder()
                                .userId("user1")
                                .roles(Set.of("user"))
                                .build();

                PolicyDecision decision = policyService.evaluate(
                                authContext, "GET", "/api/users/user1/profile", Map.of(), Map.of()).await()
                                .atMost(Duration.ofSeconds(5));

                assertTrue(decision.allowed());
        }

        @Test
        void testEvaluate_UserCannotAccessOtherProfile() {
                AuthContext authContext = AuthContext.builder()
                                .userId("user1")
                                .roles(Set.of("user"))
                                .build();

                PolicyDecision decision = policyService.evaluate(
                                authContext, "GET", "/api/users/user2/profile", Map.of(), Map.of()).await()
                                .atMost(Duration.ofSeconds(5));

                assertFalse(decision.allowed());
        }

        @Test
        void testEvaluate_SensitiveDataAccess() {
                AuthContext authContext = AuthContext.builder()
                                .userId("user1")
                                .permissions(Set.of("sensitive-data-access"))
                                .build();

                PolicyDecision decision = policyService.evaluate(
                                authContext, "GET", "/api/sensitive/secrets", Map.of(), Map.of()).await()
                                .atMost(Duration.ofSeconds(5));

                assertTrue(decision.allowed());
        }

        @Test
        void testEvaluate_SensitiveDataDenied() {
                AuthContext authContext = AuthContext.builder()
                                .userId("user1")
                                .roles(Set.of("admin")) // Even admin needs explicit permission for /api/sensitive in my
                                                        // Rego
                                .build();

                PolicyDecision decision = policyService.evaluate(
                                authContext, "GET", "/api/sensitive/secrets", Map.of(), Map.of()).await()
                                .atMost(Duration.ofSeconds(5));

                assertFalse(decision.allowed());
        }

        @Test
        void testEvaluate_ViewerCanReadGenericResources() {
                AuthContext authContext = AuthContext.builder()
                                .userId("v1")
                                .roles(Set.of("viewer"))
                                .build();

                PolicyDecision decision = policyService.evaluate(
                                authContext, "GET", "/api/products", Map.of(), Map.of()).await()
                                .atMost(Duration.ofSeconds(5));

                assertTrue(decision.allowed());
        }

        @Test
        void testEvaluate_UserCanWriteGenericResources() {
                AuthContext authContext = AuthContext.builder()
                                .userId("u1")
                                .roles(Set.of("user"))
                                .build();

                PolicyDecision decision = policyService.evaluate(
                                authContext, "POST", "/api/products", Map.of(), Map.of()).await()
                                .atMost(Duration.ofSeconds(5));

                assertTrue(decision.allowed());
        }

        @Test
        void testPolicyDecision_Allow() {
                PolicyDecision decision = PolicyDecision.allow();

                assertTrue(decision.allowed());
                assertNull(decision.reason());
                assertTrue(decision.violations().isEmpty());
        }

        @Test
        void testPolicyDecision_DenyWithReason() {
                PolicyDecision decision = PolicyDecision.deny("Access denied");

                assertFalse(decision.allowed());
                assertEquals("Access denied", decision.reason());
                assertTrue(decision.violations().isEmpty());
        }

        @Test
        void testPolicyDecision_DenyWithViolations() {
                PolicyDecision decision = PolicyDecision.deny(
                                "Multiple violations",
                                List.of("Missing role: admin", "Expired token"));

                assertFalse(decision.allowed());
                assertEquals(2, decision.violations().size());
                assertTrue(decision.firstViolation().isPresent());
                assertEquals("Missing role: admin", decision.firstViolation().get());
        }

        @Test
        void testParsePolicyResult_NullResult() {
                PolicyDecision decision = PolicyService.parsePolicyResult(null);
                assertFalse(decision.allowed());
                assertEquals("No result from OPA", decision.reason());
        }

        @Test
        void testParsePolicyResult_BooleanTrue() {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode node = mapper.getNodeFactory().booleanNode(true);
                PolicyDecision decision = PolicyService.parsePolicyResult(node);
                assertTrue(decision.allowed());
        }

        @Test
        void testParsePolicyResult_BooleanFalse() {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode node = mapper.getNodeFactory().booleanNode(false);
                PolicyDecision decision = PolicyService.parsePolicyResult(node);
                assertFalse(decision.allowed());
        }

        @Test
        void testParsePolicyResult_ObjectAllowTrue() {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
                node.put("allow", true);
                PolicyDecision decision = PolicyService.parsePolicyResult(node);
                assertTrue(decision.allowed());
        }

        @Test
        void testParsePolicyResult_ObjectAllowFalse_WithReasonAndViolations() {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
                node.put("allow", false);
                node.put("reason", "Custom Reason");
                com.fasterxml.jackson.databind.node.ArrayNode v = node.putArray("violations");
                v.add("Missing permission");

                PolicyDecision decision = PolicyService.parsePolicyResult(node);
                assertFalse(decision.allowed());
                assertEquals("Custom Reason", decision.reason());
                assertEquals(1, decision.violations().size());
                assertEquals("Missing permission", decision.firstViolation().get());
        }

        @Test
        void testPolicyService_Evaluate_OpaDisabled() {
                // Mock config where opa.enabled = false
                space.maatini.sidecar.config.SidecarConfig mockConfig = org.mockito.Mockito
                                .mock(space.maatini.sidecar.config.SidecarConfig.class);
                space.maatini.sidecar.config.SidecarConfig.OpaConfig mockOpa = org.mockito.Mockito
                                .mock(space.maatini.sidecar.config.SidecarConfig.OpaConfig.class);
                org.mockito.Mockito.when(mockConfig.opa()).thenReturn(mockOpa);
                org.mockito.Mockito.when(mockOpa.enabled()).thenReturn(false);

                PolicyService svc = new PolicyService();
                svc.config = mockConfig;
                PolicyDecision decision = svc.evaluate(AuthContext.anonymous(), "GET", "/", Map.of(), Map.of()).await()
                                .indefinitely();
                assertTrue(decision.allowed());
        }

        @Test
        void testParsePolicyResult_InvalidFormat() {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode node = mapper.createArrayNode();
                PolicyDecision decision = PolicyService.parsePolicyResult(node);
                assertFalse(decision.allowed());
                assertEquals("Unexpected OPA response format", decision.reason());
        }

        @Test
        void testParsePolicyResult_ObjectMissingAllow() {
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode node = mapper.createObjectNode().put("someOtherField", true);
                PolicyDecision decision = PolicyService.parsePolicyResult(node);
                assertFalse(decision.allowed());
        }

        @Test
        void testFallbackEvaluateExternal() {
                PolicyDecision decision = policyService.fallbackEvaluateExternal(
                                space.maatini.sidecar.model.PolicyInput.from(AuthContext.anonymous(), "GET", "/test",
                                                Map.of(), Map.of()),
                                new RuntimeException("Test Exception")).await().indefinitely();
                assertFalse(decision.allowed());
                assertTrue(decision.reason().contains("unavailable"));
        }
}
