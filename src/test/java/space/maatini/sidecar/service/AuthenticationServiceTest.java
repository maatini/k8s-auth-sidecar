package space.maatini.sidecar.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import space.maatini.sidecar.model.AuthContext;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for AuthenticationService.
 */
@QuarkusTest
class AuthenticationServiceTest {

    @Inject
    AuthenticationService authenticationService;

    @Test
    void testExtractFromJwt_Keycloak() {
        JsonWebToken jwt = Mockito.mock(JsonWebToken.class);
        
        when(jwt.getSubject()).thenReturn("user-123");
        when(jwt.getIssuer()).thenReturn("https://keycloak.example.com/realms/myrealm");
        when(jwt.getClaim("email")).thenReturn("user@example.com");
        when(jwt.getClaim("name")).thenReturn("Test User");
        when(jwt.getClaim("preferred_username")).thenReturn("testuser");
        when(jwt.getClaim("iat")).thenReturn(1704067200L);
        when(jwt.getClaim("exp")).thenReturn(1704070800L);
        when(jwt.getClaim("jti")).thenReturn("token-id-123");
        when(jwt.getClaimNames()).thenReturn(Set.of("sub", "email", "name"));
        
        // Keycloak realm_access claim
        Map<String, Object> realmAccess = Map.of("roles", Set.of("user", "admin"));
        when(jwt.getClaim("realm_access")).thenReturn(realmAccess);
        when(jwt.getClaim("resource_access")).thenReturn(null);
        when(jwt.getAudience()).thenReturn(Set.of("my-client"));

        AuthContext context = authenticationService.extractFromJwt(jwt);

        assertNotNull(context);
        assertEquals("user-123", context.userId());
        assertEquals("user@example.com", context.email());
        assertEquals("Test User", context.name());
        assertEquals("myrealm", context.tenant());
        assertTrue(context.roles().contains("user"));
        assertTrue(context.roles().contains("admin"));
        assertTrue(context.isAuthenticated());
    }

    @Test
    void testExtractFromJwt_EntraId() {
        JsonWebToken jwt = Mockito.mock(JsonWebToken.class);
        
        when(jwt.getSubject()).thenReturn("azure-sub-123");
        when(jwt.getIssuer()).thenReturn("https://login.microsoftonline.com/tenant-id/v2.0");
        when(jwt.getClaim("oid")).thenReturn("azure-oid-456");
        when(jwt.getClaim("tid")).thenReturn("tenant-id");
        when(jwt.getClaim("email")).thenReturn("user@azure.example.com");
        when(jwt.getClaim("name")).thenReturn("Azure User");
        when(jwt.getClaim("upn")).thenReturn("user@azure.example.com");
        when(jwt.getClaim("preferred_username")).thenReturn(null);
        when(jwt.getClaim("iat")).thenReturn(1704067200L);
        when(jwt.getClaim("exp")).thenReturn(1704070800L);
        when(jwt.getClaim("jti")).thenReturn("azure-token-id");
        when(jwt.getClaimNames()).thenReturn(Set.of("sub", "oid", "tid", "email", "name"));
        
        // Entra ID roles claim
        when(jwt.getClaim("roles")).thenReturn(Set.of("User.Read", "User.Write"));
        when(jwt.getClaim("groups")).thenReturn(Set.of("group-1", "group-2"));
        when(jwt.getClaim("realm_access")).thenReturn(null);
        when(jwt.getAudience()).thenReturn(Set.of("api://my-app"));

        AuthContext context = authenticationService.extractFromJwt(jwt);

        assertNotNull(context);
        // Entra ID uses 'oid' as user ID
        assertEquals("azure-oid-456", context.userId());
        assertEquals("user@azure.example.com", context.email());
        assertEquals("Azure User", context.name());
        assertEquals("tenant-id", context.tenant());
        assertTrue(context.roles().contains("User.Read"));
        assertTrue(context.roles().contains("User.Write"));
        assertTrue(context.roles().contains("group-1"));
        assertTrue(context.isAuthenticated());
    }

    @Test
    void testExtractFromJwt_NullToken() {
        AuthContext context = authenticationService.extractFromJwt(null);
        
        assertNotNull(context);
        assertFalse(context.isAuthenticated());
        assertEquals("anonymous", context.userId());
    }

    @Test
    void testAuthContext_HasRole() {
        AuthContext context = AuthContext.builder()
            .userId("user-123")
            .roles(Set.of("user", "admin", "viewer"))
            .build();

        assertTrue(context.hasRole("user"));
        assertTrue(context.hasRole("admin"));
        assertFalse(context.hasRole("superadmin"));
    }

    @Test
    void testAuthContext_HasAnyRole() {
        AuthContext context = AuthContext.builder()
            .userId("user-123")
            .roles(Set.of("viewer"))
            .build();

        assertTrue(context.hasAnyRole("admin", "viewer", "user"));
        assertFalse(context.hasAnyRole("admin", "superadmin"));
    }

    @Test
    void testAuthContext_HasAllRoles() {
        AuthContext context = AuthContext.builder()
            .userId("user-123")
            .roles(Set.of("user", "admin"))
            .build();

        assertTrue(context.hasAllRoles("user", "admin"));
        assertFalse(context.hasAllRoles("user", "admin", "superadmin"));
    }
}
