package de.edeka.eit.sidecar.application.service;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import de.edeka.eit.sidecar.domain.model.AuthContext;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class AuthenticationServiceTest {

    private AuthenticationService authenticationService;
    private KeycloakRoleExtractor roleExtractor;
    private AuthContextMapper authContextMapper;

    @BeforeEach
    void setup() {
        roleExtractor = Mockito.mock(KeycloakRoleExtractor.class);
        authContextMapper = Mockito.mock(AuthContextMapper.class);
        authenticationService = new AuthenticationService(roleExtractor, authContextMapper);
    }

    @Test
    void testExtractFromJwt_Normal() {
        JsonWebToken jwt = Mockito.mock(JsonWebToken.class);

        when(jwt.getSubject()).thenReturn("user-123");
        when(jwt.getIssuer()).thenReturn("https://keycloak.example.com/realms/myrealm");
        when(jwt.getClaim("email")).thenReturn("user@example.com");
        when(jwt.getClaim("name")).thenReturn("Test User");
        when(jwt.getClaimNames()).thenReturn(Set.of("sub", "email", "name"));
        when(jwt.getRawToken()).thenReturn("test-raw-token-789");

        Set<String> roles = Set.of("user", "admin");
        when(roleExtractor.extractRoles(jwt)).thenReturn(roles);
        
        AuthContext expectedContext = AuthContext.builder()
                .userId("user-123")
                .email("user@example.com")
                .name("Test User")
                .roles(roles)
                .build();
                
        when(authContextMapper.mapToAuthContext(jwt, roles)).thenReturn(expectedContext);

        AuthContext context = authenticationService.extractFromJwt(jwt);

        assertNotNull(context);
        assertEquals("user-123", context.userId());
        assertEquals("user@example.com", context.email());
        assertEquals("Test User", context.name());
        assertTrue(context.roles().contains("user"));
        assertTrue(context.roles().contains("admin"));
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
                .roles(Set.of("user", "admin"))
                .build();

        assertTrue(context.hasRole("user"));
        assertTrue(context.hasRole("admin"));
        assertFalse(context.hasRole("viewer"));
    }
}
