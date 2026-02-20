package space.maatini.sidecar.service;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.model.PolicyDecision;
import space.maatini.sidecar.test.OpaTestResource;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(OpaTestResource.class)
public class PolicyServiceRegoTest {

    @Inject
    PolicyService policyService;

    @Test
    void testSuperadminCanAccessAnything() {
        AuthContext context = AuthContext.builder()
                .userId("u1")
                .roles(Set.of("superadmin"))
                .build();

        PolicyDecision decision = policyService.evaluate(context, "DELETE", "/api/super-secret", Map.of(), Map.of())
                .await().indefinitely();

        assertTrue(decision.allowed());
    }

    @Test
    void testAdminPathsRequireAdminRole() {
        AuthContext adminContext = AuthContext.builder()
                .userId("u2")
                .roles(Set.of("admin"))
                .build();

        PolicyDecision adminDecision = policyService
                .evaluate(adminContext, "GET", "/api/admin/settings", Map.of(), Map.of())
                .await().indefinitely();
        assertTrue(adminDecision.allowed());

        AuthContext userContext = AuthContext.builder()
                .userId("u3")
                .roles(Set.of("user"))
                .build();

        PolicyDecision userDecision = policyService
                .evaluate(userContext, "GET", "/api/admin/settings", Map.of(), Map.of())
                .await().indefinitely();
        assertFalse(userDecision.allowed());
    }

    @Test
    void testUserCanAccessOwnResources() {
        AuthContext context = AuthContext.builder()
                .userId("12345")
                .roles(Set.of("user"))
                .build();

        PolicyDecision allowed = policyService.evaluate(context, "GET", "/api/users/12345/profile", Map.of(), Map.of())
                .await().indefinitely();
        assertTrue(allowed.allowed());

        PolicyDecision denied = policyService.evaluate(context, "GET", "/api/users/67890/profile", Map.of(), Map.of())
                .await().indefinitely();
        assertFalse(denied.allowed());
    }

    @Test
    void testPublicPathsFreeForAll() {
        AuthContext anonymousContext = AuthContext.anonymous();

        PolicyDecision decision = policyService
                .evaluate(anonymousContext, "GET", "/api/public/info", Map.of(), Map.of())
                .await().indefinitely();

        assertTrue(decision.allowed());
    }

    @Test
    void testDefaultDenyWithReason() {
        AuthContext context = AuthContext.builder()
                .userId("u4")
                .roles(Set.of("unknown-role"))
                .build();

        PolicyDecision decision = policyService.evaluate(context, "GET", "/api/sensitive-data", Map.of(), Map.of())
                .await().indefinitely();

        assertFalse(decision.allowed());
        assertEquals("Access denied by policy", decision.reason());
    }
}
