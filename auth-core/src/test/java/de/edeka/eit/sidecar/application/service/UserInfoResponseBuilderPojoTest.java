package de.edeka.eit.sidecar.application.service;

import de.edeka.eit.sidecar.domain.model.AuthContext;
import de.edeka.eit.sidecar.domain.model.UserInfoResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * POJO tests for UserInfoResponseBuilder (no Quarkus context needed).
 */
class UserInfoResponseBuilderPojoTest {

    private UserInfoResponseBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new UserInfoResponseBuilder();
    }

    @Test
    void build_MapsAllFieldsFromAuthContext() {
        AuthContext ctx = AuthContext.builder()
                .userId("alice@company.com")
                .name("Alice Müller")
                .preferredUsername("alice")
                .email("alice@company.com")
                .roles(Set.of("admin", "editor"))
                .permissions(Set.of("orders:read", "orders:write", "invoices:approve"))
                .issuedAt(1742208000L)
                .expiresAt(1742244000L)
                .build();

        UserInfoResponse response = builder.build(ctx);

        assertEquals("alice@company.com", response.sub());
        assertEquals("Alice Müller", response.name());
        assertEquals("alice", response.preferredUsername());
        assertEquals("alice@company.com", response.email());
        assertEquals(1742244000L, response.exp());
        assertEquals(1742208000L, response.iat());
    }

    @Test
    void build_SortsRolesAlphabetically() {
        AuthContext ctx = AuthContext.builder()
                .userId("user1")
                .roles(Set.of("editor", "admin", "viewer"))
                .build();

        UserInfoResponse response = builder.build(ctx);

        assertEquals(List.of("admin", "editor", "viewer"), response.roles());
    }

    @Test
    void build_GeneratesSortedRightsList() {
        AuthContext ctx = AuthContext.builder()
                .userId("user1")
                .permissions(Set.of("orders:write", "invoices:approve", "orders:read"))
                .build();

        UserInfoResponse response = builder.build(ctx);

        assertEquals(List.of("invoices:approve", "orders:read", "orders:write"), response.rights());
    }

    @Test
    void groupPermissions_GroupsByColonSeparator() {
        Set<String> perms = Set.of("orders:read", "orders:write", "orders:delete",
                "invoices:read", "invoices:approve", "users:manage");

        Map<String, List<String>> grouped = builder.groupPermissions(perms);

        assertEquals(List.of("delete", "read", "write"), grouped.get("orders"));
        assertEquals(List.of("approve", "read"), grouped.get("invoices"));
        assertEquals(List.of("manage"), grouped.get("users"));
        assertEquals(3, grouped.size());
    }

    @Test
    void groupPermissions_WithoutColon_PlacedUnderGlobalKey() {
        Set<String> perms = Set.of("superadmin", "orders:read");

        Map<String, List<String>> grouped = builder.groupPermissions(perms);

        assertEquals(List.of("superadmin"), grouped.get("_global"));
        assertEquals(List.of("read"), grouped.get("orders"));
    }

    @Test
    void groupPermissions_EmptySet_ReturnsEmptyMap() {
        Map<String, List<String>> grouped = builder.groupPermissions(Collections.emptySet());

        assertTrue(grouped.isEmpty());
    }

    @Test
    void groupPermissions_NullSet_ReturnsEmptyMap() {
        Map<String, List<String>> grouped = builder.groupPermissions(null);

        assertTrue(grouped.isEmpty());
    }

    @Test
    void build_WithNullRolesAndPermissions_ReturnsEmptyCollections() {
        AuthContext ctx = AuthContext.builder()
                .userId("user1")
                .build();

        UserInfoResponse response = builder.build(ctx);

        assertNotNull(response.roles());
        assertTrue(response.roles().isEmpty());
        assertNotNull(response.rights());
        assertTrue(response.rights().isEmpty());
        assertNotNull(response.permissions());
        assertTrue(response.permissions().isEmpty());
    }

    @Test
    void build_FullExample_MatchesExpectedFormat() {
        AuthContext ctx = AuthContext.builder()
                .userId("alice@company.com")
                .name("Alice Müller")
                .preferredUsername("alice")
                .email("alice@company.com")
                .roles(Set.of("admin", "editor"))
                .permissions(Set.of("orders:read", "orders:write", "orders:delete",
                        "invoices:read", "invoices:approve", "users:manage"))
                .issuedAt(1742208000L)
                .expiresAt(1742244000L)
                .build();

        UserInfoResponse response = builder.build(ctx);

        // Verify the complete structure
        assertEquals("alice@company.com", response.sub());
        assertEquals(List.of("admin", "editor"), response.roles());
        assertEquals(3, response.permissions().size());
        assertTrue(response.permissions().get("orders").containsAll(List.of("read", "write", "delete")));
        assertTrue(response.permissions().get("invoices").containsAll(List.of("read", "approve")));
        assertEquals(List.of("manage"), response.permissions().get("users"));
    }
}
