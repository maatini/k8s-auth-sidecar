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
                authContext, "GET", "/api/admin/settings", Map.of(), Map.of()).await().atMost(Duration.ofSeconds(5));

        assertTrue(decision.allowed());
    }

    @Test
    void testEvaluate_AdminAccessToAdminPath() {
        AuthContext authContext = AuthContext.builder()
                .userId("admin-user")
                .roles(Set.of("admin"))
                .build();

        PolicyDecision decision = policyService.evaluate(
                authContext, "GET", "/api/admin/settings", Map.of(), Map.of()).await().atMost(Duration.ofSeconds(5));

        assertTrue(decision.allowed());
    }

    @Test
    void testEvaluate_UserDeniedAdminPath() {
        AuthContext authContext = AuthContext.builder()
                .userId("regular-user")
                .roles(Set.of("user"))
                .build();

        PolicyDecision decision = policyService.evaluate(
                authContext, "GET", "/api/admin/settings", Map.of(), Map.of()).await().atMost(Duration.ofSeconds(5));

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
                authContext, "GET", "/api/users", Map.of(), Map.of()).await().atMost(Duration.ofSeconds(5));

        assertTrue(decision.allowed());
    }

    @Test
    void testEvaluate_ViewerCannotWrite() {
        AuthContext authContext = AuthContext.builder()
                .userId("viewer-user")
                .roles(Set.of("viewer"))
                .build();

        PolicyDecision decision = policyService.evaluate(
                authContext, "POST", "/api/users", Map.of(), Map.of()).await().atMost(Duration.ofSeconds(5));

        assertFalse(decision.allowed());
    }

    @Test
    void testEvaluate_UnauthenticatedDenied() {
        AuthContext authContext = AuthContext.anonymous();

        PolicyDecision decision = policyService.evaluate(
                authContext, "GET", "/api/users", Map.of(), Map.of()).await().atMost(Duration.ofSeconds(5));

        assertFalse(decision.allowed());
        assertNotNull(decision.reason());
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
}
