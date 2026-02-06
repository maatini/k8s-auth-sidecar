package space.maatini.sidecar.service;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(MultiTenantResolverTest.Profile.class)
class MultiTenantResolverTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("quarkus.oidc.tenant-enabled", "true");
        }
    }

    @Inject
    MultiTenantResolver multiTenantResolver;

    @Test
    void testResolveDefault() {
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(context.request()).thenReturn(request);
        when(request.getHeader(anyString())).thenReturn(null);

        // Should return default (keycloak) tenant if nothing specified
        String tenant = multiTenantResolver.resolve(context);
        assertEquals("default", tenant);
    }

    @Test
    void testResolveFromHeader() {
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(context.request()).thenReturn(request);
        when(request.getHeader("X-Tenant-ID")).thenReturn("my-tenant");

        String tenant = multiTenantResolver.resolve(context);
        assertEquals("my-tenant", tenant);
    }

    @Test
    void testResolveFromKeycloakToken() {
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(context.request()).thenReturn(request);
        when(request.getHeader("X-Tenant-ID")).thenReturn(null);

        // Create a fake token with Keycloak issuer
        String payload = "{\"iss\":\"https://keycloak.example.com/realms/myrealm\"}";
        String token = "header." + Base64.getUrlEncoder().encodeToString(payload.getBytes()) + ".signature";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        String tenant = multiTenantResolver.resolve(context);
        assertEquals("default", tenant); // Logic maps keycloak issuer to default tenant
    }

    @Test
    void testResolveFromEntraIdToken() {
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(context.request()).thenReturn(request);
        when(request.getHeader("X-Tenant-ID")).thenReturn(null);

        // Create a fake token with Entra ID issuer
        String payload = "{\"iss\":\"https://login.microsoftonline.com/common/v2.0\"}";
        String token = "header." + Base64.getUrlEncoder().encodeToString(payload.getBytes()) + ".signature";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        String tenant = multiTenantResolver.resolve(context);
        assertEquals("entra", tenant);
    }

    @Test
    void testResolveFromUnknownToken() {
        RoutingContext context = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(context.request()).thenReturn(request);
        
        // Invalid token format
        when(request.getHeader("Authorization")).thenReturn("Bearer invalid.token");

        String tenant = multiTenantResolver.resolve(context);
        assertEquals("default", tenant);
    }
}
