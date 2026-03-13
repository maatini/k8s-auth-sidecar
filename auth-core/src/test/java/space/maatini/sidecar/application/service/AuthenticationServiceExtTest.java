package space.maatini.sidecar.application.service;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.domain.model.AuthContext;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for AuthenticationService and sub-components.
 * Replaces reflection-based tests with direct calls to public interfaces.
 */
public class AuthenticationServiceExtTest {

    private AuthenticationService service;
    private KeycloakRoleExtractorImpl roleExtractor;
    private JwtClaimExtractorImpl claimExtractor;
    private AuthContextMapperImpl authContextMapper;

    @BeforeEach
    void setup() {
        claimExtractor = new JwtClaimExtractorImpl();

        roleExtractor = new KeycloakRoleExtractorImpl();
        roleExtractor.claimExtractor = claimExtractor;

        authContextMapper = new AuthContextMapperImpl();
        authContextMapper.claimExtractor = claimExtractor;

        service = new AuthenticationService(roleExtractor, authContextMapper);
    }

    // --- AuthenticationService lifecycle tests ---

    @Test
    void testExtractAuthContext_NullJwt() {
        Uni<AuthContext> uni = service.extractAuthContext(null);
        AuthContext ctx = uni.await().indefinitely();
        assertFalse(ctx.isAuthenticated());
        assertEquals("anonymous", ctx.userId());
    }

    @Test
    void testExtractAuthContext_ExceptionFailure() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getSubject()).thenThrow(new RuntimeException("Test exception"));
        assertThrows(RuntimeException.class, () -> service.extractAuthContext(jwt).await().indefinitely());
    }

    // --- KeycloakRoleExtractorImpl tests (formerly private methods in AuthenticationService) ---

    @Test
    void testExtractGroups_ThrowsException() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getGroups()).thenThrow(new RuntimeException("No groups"));
        Set<String> groups = roleExtractor.extractGroups(jwt);
        assertTrue(groups.isEmpty());
    }

    @Test
    void testExtractGroups_NullGroups() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getGroups()).thenReturn(null);
        Set<String> groups = roleExtractor.extractGroups(jwt);
        assertTrue(groups.isEmpty());
    }

    @Test
    void testExtractRealmRoles_NotACollection() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        Map<String, Object> realmAccess = Map.of("roles", "not-a-list");
        when(jwt.getClaim("realm_access")).thenReturn(realmAccess);
        Set<String> roles = roleExtractor.extractRealmRoles(jwt);
        assertTrue(roles.isEmpty());
    }

    @Test
    void testExtractRealmRoles_NullRoles() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        Map<String, Object> realmAccess = new java.util.HashMap<>();
        realmAccess.put("roles", null);
        when(jwt.getClaim("realm_access")).thenReturn(realmAccess);
        Set<String> roles = roleExtractor.extractRealmRoles(jwt);
        assertTrue(roles.isEmpty());
    }

    @Test
    void testExtractResourceRoles_EdgeCases() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        Map<String, Object> resourceAccess = new java.util.HashMap<>();
        resourceAccess.put("client1", "not-a-map");
        resourceAccess.put("client2", Map.of("other-key", "value"));
        resourceAccess.put("client3", Map.of("roles", "not-a-collection"));
        when(jwt.getClaim("resource_access")).thenReturn(resourceAccess);
        Set<String> roles = roleExtractor.extractResourceRoles(jwt);
        assertTrue(roles.isEmpty());
    }

    @Test
    void testExtractResourceRoles_Single() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        Map<String, Object> clientAccess = new java.util.HashMap<>();
        clientAccess.put("roles", List.of("r1"));
        Map<String, Object> resourceAccess = new java.util.HashMap<>();
        resourceAccess.put("my-client", clientAccess);
        when(jwt.getClaim("resource_access")).thenReturn(resourceAccess);
        Set<String> roles = roleExtractor.extractResourceRoles(jwt);
        assertTrue(roles.contains("my-client:r1"));
    }

    // --- JwtClaimExtractorImpl tests (formerly private methods in AuthenticationService) ---

    @Test
    void testExtractLongClaim_NumberValue() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getClaim("amount")).thenReturn(Integer.valueOf(42));
        long result = claimExtractor.extractLongClaim(jwt, "amount");
        assertEquals(42L, result);
    }

    // --- AuthContextMapperImpl audience tests ---

    @Test
    void testAudience_Set() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getSubject()).thenReturn("u1");
        when(jwt.getAudience()).thenReturn(Set.of("aud1", "aud2"));
        AuthContext ctx = authContextMapper.mapToAuthContext(jwt, Set.of());
        assertEquals(2, ctx.audience().size());
        assertTrue(ctx.audience().contains("aud1"));
    }

    @Test
    void testAudience_Null() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getSubject()).thenReturn("u1");
        when(jwt.getAudience()).thenReturn(null);
        AuthContext ctx = authContextMapper.mapToAuthContext(jwt, Set.of());
        assertTrue(ctx.audience().isEmpty());
    }

    // --- Full integration through AuthenticationService.extractFromJwt ---

    @Test
    void testExtractFromJwt_FullData() {
        JsonWebToken jwt = mock(JsonWebToken.class);

        when(jwt.getSubject()).thenReturn("user-999");
        when(jwt.getIssuer()).thenReturn("https://auth.com");
        when(jwt.getClaim("email")).thenReturn("full@test.com");
        when(jwt.getClaim("name")).thenReturn("Full Person");
        when(jwt.getClaim("preferred_username")).thenReturn("fullp");
        when(jwt.getClaim("iat")).thenReturn(1700000000L);
        when(jwt.getClaim("exp")).thenReturn(1700003600L);
        when(jwt.getClaim("jti")).thenReturn("token-id-123");
        when(jwt.getRawToken()).thenReturn("full-raw-token-999");
        when(jwt.getGroups()).thenReturn(Set.of("group-a", "group-b"));
        when(jwt.getClaim("realm_access")).thenReturn(Map.of("roles", List.of("power-user")));
        when(jwt.getClaim("resource_access")).thenReturn(Map.of("app1", Map.of("roles", List.of("admin"))));
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
        assertTrue(context.roles().contains("power-user"));
        assertTrue(context.roles().contains("app1:admin"));
        assertEquals("custom-val", context.claims().get("custom"));
    }
}
