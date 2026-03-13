package space.maatini.sidecar.application.service;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.domain.model.AuthContext;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationServicePojoTest {

    private AuthenticationService authenticationService;

    @BeforeEach
    void setup() {
        JwtClaimExtractorImpl claimExtractor = new JwtClaimExtractorImpl();

        KeycloakRoleExtractorImpl roleExtractor = new KeycloakRoleExtractorImpl();
        roleExtractor.claimExtractor = claimExtractor;

        AuthContextMapperImpl authContextMapper = new AuthContextMapperImpl();
        authContextMapper.claimExtractor = claimExtractor;

        authenticationService = new AuthenticationService(roleExtractor, authContextMapper);
    }

    @Test
    void testExtractAuthContext_ValidJwt() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getSubject()).thenReturn("user123");
        when(jwt.getIssuer()).thenReturn("https://issuer");
        when(jwt.getClaimNames()).thenReturn(Set.of("sub", "iss"));
        when(jwt.getClaim("sub")).thenReturn("user123");
        when(jwt.getClaim("iss")).thenReturn("https://issuer");
        when(jwt.getRawToken()).thenReturn("mock-raw-token-123");

        AuthContext context = authenticationService.extractAuthContext(jwt).await().indefinitely();
        assertTrue(context.isAuthenticated());
        assertEquals("user123", context.userId());
        assertEquals("https://issuer", context.issuer());
    }

    @Test
    void testExtractFromJwt_Normal() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getSubject()).thenReturn("user-456");
        when(jwt.getIssuer()).thenReturn("https://keycloak");
        when(jwt.getClaim("email")).thenReturn("user@example.com");
        when(jwt.getClaim("preferred_username")).thenReturn("jdoe");
        when(jwt.getClaim("name")).thenReturn("John Doe");
        when(jwt.getRawToken()).thenReturn("mock-raw-token-456");

        Map<String, Object> realmAccess = Map.of("roles", List.of("admin", "user"));
        when(jwt.getClaim("realm_access")).thenReturn(realmAccess);

        Map<String, Object> resourceAccess = Map.of(
                "client1", Map.of("roles", List.of("editor")),
                "client2", Map.of("roles", List.of("viewer")));
        when(jwt.getClaim("resource_access")).thenReturn(resourceAccess);

        AuthContext context = authenticationService.extractFromJwt(jwt);

        assertEquals("user-456", context.userId());
        assertEquals("user@example.com", context.email());
        assertEquals("John Doe", context.name());
        assertEquals("jdoe", context.preferredUsername());

        assertTrue(context.roles().contains("admin"));
        assertTrue(context.roles().contains("user"));
        assertTrue(context.roles().contains("client1:editor"));
        assertTrue(context.roles().contains("client2:viewer"));
    }

    @Test
    void testExtractFromJwt_Null() {
        AuthContext context = authenticationService.extractFromJwt(null);
        assertFalse(context.isAuthenticated());
        assertEquals("anonymous", context.userId());
    }

    @Test
    void testExtractAllClaims_Failure() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getClaimNames()).thenThrow(new RuntimeException("Boom"));

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertNotNull(context.claims());
        assertTrue(context.claims().isEmpty());
    }

    @Test
    void testExtractFromJwt_MalformedResourceAccess() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getSubject()).thenReturn("user-456");
        when(jwt.getClaim("resource_access")).thenReturn("Not a Map");

        AuthContext context = authenticationService.extractFromJwt(jwt);

        assertEquals("user-456", context.userId());
        assertTrue(context.roles().isEmpty());
    }

    @Test
    void testExtractFromJwt_MalformedRealmAccess() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getSubject()).thenReturn("user-456");
        when(jwt.getClaim("realm_access")).thenReturn(Map.of("wrong_key", "value"));

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertTrue(context.roles().isEmpty());
    }

    @Test
    void testExtractAudience_SingleString() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getSubject()).thenReturn("u1");
        when(jwt.getClaimNames()).thenReturn(Set.of("aud"));
        when(jwt.getClaim("aud")).thenReturn("my-audience");

        AuthContext context = authenticationService.extractFromJwt(jwt);
        assertNotNull(context.audience());
    }
}
