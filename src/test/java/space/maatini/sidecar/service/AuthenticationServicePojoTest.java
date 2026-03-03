package space.maatini.sidecar.service;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;
import io.quarkus.security.identity.SecurityIdentity;

import java.lang.reflect.Field;
import java.security.Principal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationServicePojoTest {

    private AuthenticationService authenticationService;
    private SidecarConfig config;

    @BeforeEach
    void setup() throws Exception {
        authenticationService = new AuthenticationService();
        config = mock(SidecarConfig.class);
        setField(authenticationService, "config", config);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testExtractAuthContext_NullIdentity() {
        AuthContext result = authenticationService.extractAuthContext(null);
        assertFalse(result.isAuthenticated());
        assertEquals("anonymous", result.userId());
    }

    @Test
    void testExtractAuthContext_AnonymousIdentity() {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);
        AuthContext result = authenticationService.extractAuthContext(identity);
        assertFalse(result.isAuthenticated());
        assertEquals("anonymous", result.userId());
    }

    @Test
    void testExtractAuthContext_NonJwtPrincipal() {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        Principal principal = mock(Principal.class);
        when(identity.getPrincipal()).thenReturn(principal);

        AuthContext result = authenticationService.extractAuthContext(identity);
        assertFalse(result.isAuthenticated());
        assertEquals("anonymous", result.userId());
    }

    @Test
    void testExtractAuthContext_Exception() {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenThrow(new RuntimeException("Boom"));

        AuthContext result = authenticationService.extractAuthContext(identity);
        assertFalse(result.isAuthenticated());
    }

    @Test
    void testExtractFromJwt_KeycloakFull() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://keycloak.example.com/realms/myrealm");
        when(jwt.getSubject()).thenReturn("user-123");
        when(jwt.getClaim("email")).thenReturn("user@example.com");
        when(jwt.getClaim("name")).thenReturn("Test User");
        when(jwt.getClaim("preferred_username")).thenReturn("testuser");
        when(jwt.getClaim("iat")).thenReturn(1000L);
        when(jwt.getClaim("exp")).thenReturn(2000L);
        when(jwt.getClaim("jti")).thenReturn("jti-123");
        when(jwt.getAudience()).thenReturn(Set.of("aud1"));
        when(jwt.getClaimNames()).thenReturn(Set.of("sub", "email"));

        // Keycloak roles
        Map<String, Object> realmAccess = Map.of("roles", List.of("r1", "r2"));
        when(jwt.getClaim("realm_access")).thenReturn(realmAccess);

        Map<String, Object> resourceAccess = Map.of("client1", Map.of("roles", List.of("cr1")));
        when(jwt.getClaim("resource_access")).thenReturn(resourceAccess);

        AuthContext context = authenticationService.extractFromJwt(jwt);

        assertEquals("user-123", context.userId());
        assertEquals("user@example.com", context.email());
        assertEquals("myrealm", context.tenant());
        assertTrue(context.roles().contains("r1"));
        assertTrue(context.roles().contains("client1:cr1"));
    }

    @Test
    void testExtractFromJwt_EntraFull() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://login.microsoftonline.com/tenant-id/v2.0");
        when(jwt.getClaim("oid")).thenReturn("oid-123");
        when(jwt.getClaim("tid")).thenReturn("tid-123");
        when(jwt.getClaim("email")).thenReturn("user@entra.com");
        when(jwt.getClaim("roles")).thenReturn(List.of("Role1"));
        when(jwt.getClaim("groups")).thenReturn(List.of("Group1"));
        when(jwt.getClaimNames()).thenReturn(Set.of("oid", "tid"));

        AuthContext context = authenticationService.extractFromJwt(jwt);

        assertEquals("oid-123", context.userId());
        assertEquals("tid-123", context.tenant());
        assertTrue(context.roles().contains("Role1"));
        assertTrue(context.roles().contains("Group1"));
    }

    @Test
    void testExtractFromJwt_LongClaimConversions() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://some");
        when(jwt.getClaimNames()).thenReturn(Set.of("iat", "exp"));

        // Valid numbers (Integer/Long)
        when(jwt.getClaim("iat")).thenReturn(123);
        when(jwt.getClaim("exp")).thenReturn(456L);

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertEquals(123L, context.issuedAt());
        assertEquals(456L, context.expiresAt());

        // Non-number should return 0
        when(jwt.getClaim("iat")).thenReturn("not-a-number");
        context = authenticationService.extractFromJwt(jwt);
        assertEquals(0L, context.issuedAt());
    }

    @Test
    void testExtractFromJwt_KeycloakMalformedNesting() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://keycloak/realms/r");
        when(jwt.getClaimNames()).thenReturn(Set.of("resource_access"));

        // Malformed resource_access: value is NOT a map
        when(jwt.getClaim("resource_access")).thenReturn("NotAMap");
        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertTrue(context.roles().isEmpty());

        // Nested value is NOT a map
        Map<String, Object> ra = Map.of("client1", "NotAMapEither");
        when(jwt.getClaim("resource_access")).thenReturn(ra);
        context = authenticationService.extractFromJwt(jwt);
        assertTrue(context.roles().isEmpty());
    }

    @Test
    void testExtractAudience_NullAndException() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://some");

        // Null audience
        when(jwt.getAudience()).thenReturn(null);
        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertTrue(context.audience().isEmpty());

        // Exception during audience extraction
        when(jwt.getAudience()).thenThrow(new RuntimeException("AudError"));
        context = authenticationService.extractFromJwt(jwt);
        assertTrue(context.audience().isEmpty());
    }

    @Test
    void testExtractAllClaims_MissingValues() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://some");
        when(jwt.getClaimNames()).thenReturn(Set.of("c1", "c2"));

        // c1 is non-null, c2 is null
        when(jwt.getClaim("c1")).thenReturn("v1");
        when(jwt.getClaim("c2")).thenReturn(null);

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertEquals("v1", context.claims().get("c1"));
        assertFalse(context.claims().containsKey("c2"));
    }

    @Test
    void testExtractClaimAsStringSet_SingleString() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://login.microsoftonline.com/t/v2.0"); // Entra
        when(jwt.getClaimNames()).thenReturn(Set.of("roles"));

        // Single string instead of collection
        when(jwt.getClaim("roles")).thenReturn("AdminRole");

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertTrue(context.roles().contains("AdminRole"));
        assertEquals(1, context.roles().size());
    }

    @Test
    void testExtractClaim_ExceptionHandling() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://some");
        // extractClaim uses typed getClaim which might throw ClassCast or other
        when(jwt.getClaim("email")).thenThrow(new RuntimeException("Oops"));

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertNull(context.email());
    }

    @Test
    void testExtractFromJwt_PreferredUsernameUpnFallback() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://login.microsoftonline.com/t/v2.0"); // Entra
        when(jwt.getClaimNames()).thenReturn(Set.of("upn"));

        when(jwt.getClaim("preferred_username")).thenReturn(null);
        when(jwt.getClaim("upn")).thenReturn("user@upn.com");

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertEquals("user@upn.com", context.preferredUsername());
    }

    @Test
    void testExtractLongClaim_Missing() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://some");
        when(jwt.getClaimNames()).thenReturn(Set.of());
        when(jwt.getClaim("exp")).thenReturn(null);

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertEquals(0L, context.expiresAt());
    }

    @Test
    void testExtractClaimAsStringSet_Missing() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://login.microsoftonline.com/t/v2.0");
        when(jwt.getClaimNames()).thenReturn(Set.of());
        when(jwt.getClaim("roles")).thenReturn(null);

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertTrue(context.roles().isEmpty());
    }

    @Test
    void testExtractKeycloakResourceRoles_MissingClient() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://keycloak/realms/r");
        Map<String, Object> ra = Map.of("client1", Map.of("other", "no-roles-here"));
        when(jwt.getClaim("resource_access")).thenReturn(ra);

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertTrue(context.roles().isEmpty());
    }

    @Test
    void testExtractFromJwt_FloatingPointLong() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://some");
        when(jwt.getClaimNames()).thenReturn(Set.of("iat"));
        // 123.456 should be converted to 123
        when(jwt.getClaim("iat")).thenReturn(123.456);

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertEquals(123L, context.issuedAt());
    }

    @Test
    void testExtractClaimAsStringSet_InvalidType() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://login.microsoftonline.com/t/v2.0");
        when(jwt.getClaimNames()).thenReturn(Set.of("roles"));

        // Neither String nor Collection (e.g. Integer)
        when(jwt.getClaim("roles")).thenReturn(100);

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertTrue(context.roles().isEmpty());
    }

    @Test
    void testExtractKeycloakResourceRoles_NullRoles() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://keycloak/realms/r");
        // resource_access -> client1 -> missing 'roles' field
        Map<String, Object> clientAccess = new HashMap<>();
        clientAccess.put("other", "field");
        Map<String, Object> ra = Map.of("client1", clientAccess);
        when(jwt.getClaim("resource_access")).thenReturn(ra);

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertTrue(context.roles().isEmpty());
    }

    @Test
    void testExtractRoles_EntraWithNoRoles() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://login.microsoftonline.com/tenant/v2.0");
        when(jwt.getClaim("roles")).thenReturn(null);
        when(jwt.getClaim("groups")).thenReturn(null);

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertTrue(context.roles().isEmpty());
    }

    @Test
    void testExtractUserId_KeycloakWithNoSub() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://keycloak/realms/r");
        when(jwt.getSubject()).thenReturn(null);

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertNull(context.userId());
    }

    @Test
    void testExtractUserId_EntraWithNoOid() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://login.microsoftonline.com/t/v2.0");
        when(jwt.getClaim("oid")).thenReturn(null);
        when(jwt.getSubject()).thenReturn("sub-as-fallback");

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertEquals("sub-as-fallback", context.userId());
    }

    @Test
    void testExtractUserId_KeycloakWithOid_ShouldIgnoreOid() {
        // A Keycloak token that happens to have an 'oid' claim
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://keycloak/realms/r");
        when(jwt.getSubject()).thenReturn("sub-123");
        when(jwt.getClaim("oid")).thenReturn("oid-456");

        AuthContext context = authenticationService.extractFromJwt(jwt);
        // Should use 'sub' because it's not an Entra token
        assertEquals("sub-123", context.userId());
    }

    @Test
    void testExtractPreferredUsername_KeycloakWithUpn_ShouldIgnoreUpn() {
        // Keycloak token WITHOUT preferred_username but WITH upn
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://keycloak/realms/r");
        when(jwt.getClaim("preferred_username")).thenReturn(null);
        when(jwt.getClaim("upn")).thenReturn("wrong@upn.com");

        AuthContext context = authenticationService.extractFromJwt(jwt);
        // Should NOT fallback to upn for Keycloak
        assertNull(context.preferredUsername());
    }

    @Test
    void testExtractRoles_KeycloakWithEntraRoles_ShouldIgnoreEntraRoles() {
        // Keycloak token WITH 'roles' (Entra) and 'groups' (Entra) claims
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://keycloak/realms/r");
        when(jwt.getClaim("roles")).thenReturn(List.of("EntraRole"));
        when(jwt.getClaim("groups")).thenReturn(List.of("EntraGroup"));

        AuthContext context = authenticationService.extractFromJwt(jwt);
        // Should ignore these because it's Keycloak
        assertFalse(context.roles().contains("EntraRole"));
        assertFalse(context.roles().contains("EntraGroup"));
    }

    @Test
    void testExtractAudience_Success() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://some");
        when(jwt.getAudience()).thenReturn(Set.of("aud1", "aud2"));

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertEquals(2, context.audience().size());
        assertTrue(context.audience().contains("aud1"));
    }

    @Test
    void testExtractKeycloakRoles_Robustness() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://keycloak/realms/r");

        // realm_access is NOT a map
        when(jwt.getClaim("realm_access")).thenReturn("not-a-map");
        // resource_access has invalid client map
        Map<String, Object> resMap = new HashMap<>();
        resMap.put("client1", "not-a-map");
        when(jwt.getClaim("resource_access")).thenReturn(resMap);

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertTrue(context.roles().isEmpty());
    }

    @Test
    void testExtractLongClaim_InvalidType() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getIssuer()).thenReturn("https://some");
        // 'iat' is normally a number, but here it's a string
        when(jwt.getClaim("iat")).thenReturn("not-a-number");

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertEquals(0, context.issuedAt());
    }
}
