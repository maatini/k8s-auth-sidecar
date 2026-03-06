package space.maatini.sidecar.application.service;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.domain.model.AuthContext;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AuthenticationServiceExtTest {

    @Test
    void testExtractAuthContext_NullIdentity() {
        AuthenticationService service = new AuthenticationService();
        Uni<AuthContext> uni = service.extractAuthContext(null);
        AuthContext authContext = uni.await().indefinitely();
        assertFalse(authContext.isAuthenticated());
        assertEquals("anonymous", authContext.userId());
    }

    @Test
    void testExtractAuthContext_AnonymousIdentity() {
        AuthenticationService service = new AuthenticationService();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);

        Uni<AuthContext> uni = service.extractAuthContext(identity);
        AuthContext authContext = uni.await().indefinitely();
        assertFalse(authContext.isAuthenticated());
        assertEquals("anonymous", authContext.userId());
    }

    @Test
    void testExtractAuthContext_NotJsonWebToken() {
        AuthenticationService service = new AuthenticationService();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        Principal principal = mock(Principal.class);
        when(identity.getPrincipal()).thenReturn(principal);

        Uni<AuthContext> uni = service.extractAuthContext(identity);
        AuthContext authContext = uni.await().indefinitely();
        assertFalse(authContext.isAuthenticated());
    }

    @Test
    void testExtractAuthContext_ExceptionFallback() {
        AuthenticationService service = new AuthenticationService();
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        // Throw an exception when getPrincipal is called to test the catch block
        when(identity.getPrincipal()).thenThrow(new RuntimeException("Test exception"));

        Uni<AuthContext> uni = service.extractAuthContext(identity);
        AuthContext authContext = uni.await().indefinitely();
        assertFalse(authContext.isAuthenticated());
    }

    @Test
    void testExtractGroupsClaim_ThrowsException() throws Exception {
        AuthenticationService service = new AuthenticationService();
        Method m = AuthenticationService.class.getDeclaredMethod("extractGroupsClaim", JsonWebToken.class);
        m.setAccessible(true);

        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getGroups()).thenThrow(new RuntimeException("No groups for you"));

        Set<String> groups = (Set<String>) m.invoke(service, jwt);
        assertTrue(groups.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testExtractGroupsClaim_NullGroups() throws Exception {
        AuthenticationService service = new AuthenticationService();
        Method m = AuthenticationService.class.getDeclaredMethod("extractGroupsClaim", JsonWebToken.class);
        m.setAccessible(true);

        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getGroups()).thenReturn(null);

        Set<String> nullGroups = (Set<String>) m.invoke(service, jwt);
        assertTrue(nullGroups.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testExtractKeycloakRealmRoles_NotACollection() throws Exception {
        AuthenticationService service = new AuthenticationService();
        Method m = AuthenticationService.class.getDeclaredMethod("extractKeycloakRealmRoles", JsonWebToken.class);
        m.setAccessible(true);

        JsonWebToken jwt = mock(JsonWebToken.class);
        java.util.Map<String, Object> realmAccess = java.util.Map.of("roles", "not-a-list");
        when(jwt.getClaim("realm_access")).thenReturn(realmAccess);

        Set<String> roles = (Set<String>) m.invoke(service, jwt);
        assertTrue(roles.isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testExtractKeycloakResourceRoles_EdgeCases() throws Exception {
        AuthenticationService service = new AuthenticationService();
        Method m = AuthenticationService.class.getDeclaredMethod("extractKeycloakResourceRoles", JsonWebToken.class);
        m.setAccessible(true);

        JsonWebToken jwt = mock(JsonWebToken.class);
        java.util.Map<String, Object> resourceAccess = new java.util.HashMap<>();

        // 1. entry.getValue() is NOT a map
        resourceAccess.put("client1", "not-a-map");

        // 2. Map WITHOUT "roles"
        resourceAccess.put("client2", java.util.Map.of("other-key", "value"));

        // 3. Map with "roles" but rolesObj is NOT a Collection
        resourceAccess.put("client3", java.util.Map.of("roles", "not-a-collection"));

        when(jwt.getClaim("resource_access")).thenReturn(resourceAccess);

        Set<String> roles = (Set<String>) m.invoke(service, jwt);
        assertTrue(roles.isEmpty());
    }

    @Test
    void testExtractLongClaim_NumberValue() throws Exception {
        AuthenticationService service = new AuthenticationService();
        Method m = AuthenticationService.class.getDeclaredMethod("extractLongClaim", JsonWebToken.class, String.class);
        m.setAccessible(true);

        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getClaim("amount")).thenReturn(Integer.valueOf(42));

        long result = (Long) m.invoke(service, jwt, "amount");
        assertEquals(42L, result);
    }

    @Test
    void testExtractFromJwt_FullData() {
        AuthenticationService service = new AuthenticationService();
        JsonWebToken jwt = mock(JsonWebToken.class);

        when(jwt.getSubject()).thenReturn("user-999");
        when(jwt.getIssuer()).thenReturn("https://auth.com");
        when(jwt.getClaim("email")).thenReturn("full@test.com");
        when(jwt.getClaim("name")).thenReturn("Full Person");
        when(jwt.getClaim("preferred_username")).thenReturn("fullp");
        when(jwt.getClaim("iat")).thenReturn(1700000000L);
        when(jwt.getClaim("exp")).thenReturn(1700003600L);
        when(jwt.getClaim("jti")).thenReturn("token-id-123");

        // Groups
        Set<String> groups = Set.of("group-a", "group-b");
        when(jwt.getGroups()).thenReturn(groups);

        // Realm Roles
        Map<String, Object> realmAccess = Map.of("roles", List.of("power-user"));
        when(jwt.getClaim("realm_access")).thenReturn(realmAccess);

        // Resource Roles
        Map<String, Object> resourceAccess = Map.of("app1", Map.of("roles", List.of("admin")));
        when(jwt.getClaim("resource_access")).thenReturn(resourceAccess);

        // All claims
        when(jwt.getClaimNames()).thenReturn(Set.of("sub", "iss", "custom"));
        when(jwt.getClaim("custom")).thenReturn("custom-val");

        AuthContext context = service.extractFromJwt(jwt);

        assertEquals("user-999", context.userId());
        assertEquals("https://auth.com", context.issuer());
        assertEquals("full@test.com", context.email());
        assertEquals(1700000000L, context.issuedAt());
        assertEquals(1700003600L, context.expiresAt());
        assertEquals("token-id-123", context.tokenId());

        assertTrue(context.roles().contains("group-a"));
        assertTrue(context.roles().contains("group-b"));
        assertTrue(context.roles().contains("power-user"));
        assertTrue(context.roles().contains("app1:admin"));

        assertEquals("custom-val", context.claims().get("custom"));
    }

    @Test
    void testExtractKeycloakRealmRoles_NullRoles() throws Exception {
        AuthenticationService service = new AuthenticationService();
        Method m = AuthenticationService.class.getDeclaredMethod("extractKeycloakRealmRoles", JsonWebToken.class);
        m.setAccessible(true);

        JsonWebToken jwt = mock(JsonWebToken.class);
        java.util.Map<String, Object> realmAccess = new java.util.HashMap<>();
        realmAccess.put("roles", null);
        when(jwt.getClaim("realm_access")).thenReturn(realmAccess);

        @SuppressWarnings("unchecked")
        Set<String> roles = (Set<String>) m.invoke(service, jwt);
        assertTrue(roles.isEmpty());
    }

    @Test
    void testExtractAudience_List() throws Exception {
        AuthenticationService service = new AuthenticationService();
        Method m = AuthenticationService.class.getDeclaredMethod("extractAudience", JsonWebToken.class);
        m.setAccessible(true);

        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getAudience()).thenReturn(java.util.Set.of("aud1", "aud2"));

        @SuppressWarnings("unchecked")
        java.util.List<String> aud = (java.util.List<String>) m.invoke(service, jwt);
        assertEquals(2, aud.size());
        assertTrue(aud.contains("aud1"));
    }

    @Test
    void testExtractAudience_Null() throws Exception {
        AuthenticationService service = new AuthenticationService();
        Method m = AuthenticationService.class.getDeclaredMethod("extractAudience", JsonWebToken.class);
        m.setAccessible(true);

        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getAudience()).thenReturn(null);

        @SuppressWarnings("unchecked")
        java.util.List<String> aud = (java.util.List<String>) m.invoke(service, jwt);
        assertTrue(aud.isEmpty());
    }

    @Test
    void testExtractKeycloakResourceRoles_Single() throws Exception {
        AuthenticationService service = new AuthenticationService();
        Method m = AuthenticationService.class.getDeclaredMethod("extractKeycloakResourceRoles", JsonWebToken.class);
        m.setAccessible(true);

        JsonWebToken jwt = mock(JsonWebToken.class);
        java.util.Map<String, Object> resourceAccess = new java.util.HashMap<>();
        java.util.Map<String, Object> clientAccess = new java.util.HashMap<>();
        clientAccess.put("roles", List.of("r1"));
        resourceAccess.put("my-client", clientAccess);
        when(jwt.getClaim("resource_access")).thenReturn(resourceAccess);

        @SuppressWarnings("unchecked")
        Set<String> roles = (Set<String>) m.invoke(service, jwt);
        assertTrue(roles.contains("my-client:r1"));
    }
}
