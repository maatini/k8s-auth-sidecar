package space.maatini.sidecar.model;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModelPojoTest {

    @Test
    void testAuthContextBuilderAndMethods() {
        AuthContext context = AuthContext.builder()
                .userId("user1")
                .roles(Set.of("admin", "user"))
                .build();

        assertTrue(context.hasRole("admin"));
        assertTrue(context.hasAnyRole("guest", "user"));
        assertFalse(context.hasAnyRole("unknown"));
        assertTrue(context.hasAllRoles("admin"));
        assertFalse(context.hasAllRoles("admin", "unknown"));
        assertFalse(context.hasRole("superadmin"));

        AuthContext emptyContext = AuthContext.builder().build();
        assertFalse(emptyContext.hasRole("admin"));
        assertFalse(emptyContext.hasAnyRole("admin"));
        assertFalse(emptyContext.hasAllRoles("admin"));

        AuthContext anon = AuthContext.anonymous();
        assertFalse(anon.isAuthenticated());
        assertEquals("anonymous", anon.userId());
        assertFalse(anon.getClaim("test").isPresent());

        AuthContext withClaims = AuthContext.builder()
                .userId("u2")
                .claims(Map.of("key", "value", "list", List.of(1)))
                .audience(List.of("aud1"))
                .name("tester")
                .preferredUsername("test")
                .issuer("iss")
                .issuedAt(100L)
                .tokenId("tid")
                .tenant("tenantXYZ")
                .build();

        assertTrue(withClaims.getClaim("key").isPresent());
        assertEquals("value", withClaims.getClaim("key").get());
        assertTrue(withClaims.getClaim("unknown").isEmpty());

        AuthContext modified = withClaims.withRolesAndPermissions(Set.of("new_role"), Set.of("new_perm"));
        assertTrue(modified.hasRole("new_role"));
        assertTrue(modified.hasPermission("new_perm"));

        AuthContext newTenant = withClaims.withTenant("new_tenant");
        assertEquals("new_tenant", newTenant.tenant());

        AuthContext nullCases = AuthContext.builder()
                .roles(null)
                .permissions(null)
                .audience(null)
                .claims(null)
                .build();
        assertTrue(nullCases.roles().isEmpty());
    }

    @Test
    void testAuthContext_IsExpired() {
        AuthContext expired = AuthContext.builder()
                .expiresAt(System.currentTimeMillis() / 1000 - 60)
                .build();
        assertTrue(expired.isExpired());

        AuthContext valid = AuthContext.builder()
                .expiresAt(System.currentTimeMillis() / 1000 + 3600)
                .build();
        assertFalse(valid.isExpired());
    }

    @Test
    void testAuthContext_HasPermission() {
        AuthContext context = AuthContext.builder()
                .permissions(Set.of("read:all", "write:own"))
                .build();
        assertTrue(context.hasPermission("read:all"));
        assertTrue(context.hasPermission("write:own"));
        assertFalse(context.hasPermission("delete:all"));
    }

    @Test
    void testPolicyDecisionFactoryMethods() {
        PolicyDecision allow = PolicyDecision.allow();
        assertTrue(allow.allowed());
        assertNull(allow.reason());

        PolicyDecision deny = PolicyDecision.deny("Not allowed");
        assertFalse(deny.allowed());
        assertEquals("Not allowed", deny.reason());

        PolicyDecision denyWithViolations = PolicyDecision.deny(
                "Multiple errors",
                List.of("Error 1", "Error 2"));
        assertFalse(denyWithViolations.allowed());
        assertEquals(2, denyWithViolations.violations().size());
        assertEquals("Error 1", denyWithViolations.firstViolation().orElse(""));
    }

    @Test
    void testPolicyInputFromContext() {
        AuthContext context = AuthContext.builder()
                .userId("u1")
                .email("u1@exampl.com")
                .roles(Set.of("r1"))
                .build();

        PolicyInput input = PolicyInput.from(context, "GET", "/api/test", Collections.emptyMap(),
                Collections.emptyMap());

        assertEquals("GET", input.request().method());
        assertEquals("/api/test", input.request().path());
        assertEquals("u1", input.user().id());
        assertTrue(input.user().roles().contains("r1"));
        assertEquals("rr-sidecar", input.context().get("source"));
        assertNull(input.context().get("timestamp"));
    }

    @Test
    void testResourceInfo_FromPath() {
        PolicyInput.ResourceInfo info = PolicyInput.ResourceInfo.fromPath("/api/v1/users/123");
        assertEquals("users", info.type());
        assertEquals("123", info.id());

        PolicyInput.ResourceInfo empty = PolicyInput.ResourceInfo.fromPath("");
        assertNull(empty.type());
        assertNull(empty.id());

        PolicyInput.ResourceInfo root = PolicyInput.ResourceInfo.fromPath("/");
        assertNull(root.type());
        assertNull(root.id());
    }

    @Test
    void testRolesResponse_HelperMethods() {
        RolesResponse response = new RolesResponse("u1", Set.of("admin"), Set.of("read"), null);
        assertTrue(response.hasRole("admin"));
        assertFalse(response.hasRole("user"));
        assertTrue(response.hasPermission("read"));
        assertFalse(response.hasPermission("write"));

        RolesResponse empty = RolesResponse.empty("u2");
        assertEquals("u2", empty.userId());
        assertTrue(empty.roles().isEmpty());
    }
}
