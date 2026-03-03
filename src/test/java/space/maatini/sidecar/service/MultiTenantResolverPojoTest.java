package space.maatini.sidecar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class MultiTenantResolverPojoTest {

    private MultiTenantResolver resolver;
    private RoutingContext routingContext;
    private HttpServerRequest request;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() throws Exception {
        resolver = new MultiTenantResolver();
        routingContext = mock(RoutingContext.class);
        request = mock(HttpServerRequest.class);
        objectMapper = new ObjectMapper();

        when(routingContext.request()).thenReturn(request);

        setField(resolver, "tenantEnabled", true);
        setField(resolver, "authEnabled", true);
        setField(resolver, "objectMapper", objectMapper);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testResolve_Disabled() throws Exception {
        setField(resolver, "tenantEnabled", false);
        assertEquals("default", resolver.resolve(routingContext));

        setField(resolver, "tenantEnabled", true);
        setField(resolver, "authEnabled", false);
        assertEquals("default", resolver.resolve(routingContext));
    }

    @Test
    void testResolve_FromHeader() {
        when(request.getHeader("X-Tenant-ID")).thenReturn("MyCustomTenant");
        assertEquals("mycustomtenant", resolver.resolve(routingContext));
    }

    @Test
    void testResolve_FromToken_EntraID() {
        String payload = Base64.getUrlEncoder()
                .encodeToString("{\"iss\":\"https://login.microsoftonline.com/tenant/v2.0\"}".getBytes());
        String token = "header." + payload + ".sig";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        assertEquals("entra", resolver.resolve(routingContext));
    }

    @Test
    void testResolve_FromToken_Keycloak() {
        String payload = Base64.getUrlEncoder()
                .encodeToString("{\"iss\":\"https://keycloak.example.com/realms/test\"}".getBytes());
        String token = "header." + payload + ".sig";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        assertEquals("default", resolver.resolve(routingContext));
    }

    @Test
    void testResolve_FromToken_UnknownIssuer() {
        String payload = Base64.getUrlEncoder().encodeToString("{\"iss\":\"https://unknown.com\"}".getBytes());
        String token = "header." + payload + ".sig";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        assertEquals("default", resolver.resolve(routingContext));
    }

    @Test
    void testResolve_FromToken_InvalidFormat() {
        when(request.getHeader("Authorization")).thenReturn("Bearer invalid.token");
        assertEquals("default", resolver.resolve(routingContext));
    }

    @Test
    void testResolve_FromToken_InvalidJson() {
        String payload = Base64.getUrlEncoder().encodeToString("{invalid_json}".getBytes());
        String token = "header." + payload + ".sig";
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);

        assertEquals("default", resolver.resolve(routingContext));
    }

    @Test
    void testResolve_NoAuthHeader() {
        when(request.getHeader("Authorization")).thenReturn(null);
        assertEquals("default", resolver.resolve(routingContext));
    }
}
