package space.maatini.sidecar.service;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.model.AuthContext;

import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@io.quarkus.test.junit.QuarkusTest
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
}
