package space.maatini.sidecar.application.service;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import space.maatini.sidecar.domain.model.AuthContext;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@QuarkusTest
class AuthenticationServiceTest {

    @Inject
    AuthenticationService authenticationService;

    @Test
    void testExtractFromJwt_Normal() {
        JsonWebToken jwt = Mockito.mock(JsonWebToken.class);

        when(jwt.getSubject()).thenReturn("user-123");
        when(jwt.getIssuer()).thenReturn("https://keycloak.example.com/realms/myrealm");
        when(jwt.getClaim("email")).thenReturn("user@example.com");
        when(jwt.getClaim("name")).thenReturn("Test User");
        when(jwt.getClaimNames()).thenReturn(Set.of("sub", "email", "name"));

        Map<String, Object> realmAccess = Map.of("roles", Set.of("user", "admin"));
        when(jwt.getClaim("realm_access")).thenReturn(realmAccess);

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
