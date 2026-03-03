package space.maatini.sidecar.filter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.model.PolicyDecision;
import space.maatini.sidecar.service.AuthenticationService;
import space.maatini.sidecar.service.PolicyService;
import space.maatini.sidecar.service.ProxyService;
import space.maatini.sidecar.service.RolesService;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthProxyFilterPojoTest {

    private AuthProxyFilter filter;
    private SidecarConfig config;
    private AuthenticationService authService;
    private RolesService rolesService;
    private PolicyService policyService;
    private ContainerRequestContext requestContext;
    private UriInfo uriInfo;

    @BeforeEach
    void setup() throws Exception {
        filter = new AuthProxyFilter();

        config = mock(SidecarConfig.class);
        authService = mock(AuthenticationService.class);
        rolesService = mock(RolesService.class);
        policyService = mock(PolicyService.class);
        MeterRegistry registry = mock(MeterRegistry.class);
        Counter mockCounter = mock(Counter.class);
        Timer mockTimer = mock(Timer.class);

        setField(filter, "config", config);
        setField(filter, "authenticationService", authService);
        setField(filter, "rolesService", rolesService);
        setField(filter, "policyService", policyService);
        setField(filter, "meterRegistry", registry);

        setField(filter, "authSuccessCounter", mockCounter);
        setField(filter, "authFailureCounter", mockCounter);
        setField(filter, "authzAllowCounter", mockCounter);
        setField(filter, "authzDenyCounter", mockCounter);
        setField(filter, "authTimer", mockTimer);

        requestContext = mock(ContainerRequestContext.class);
        uriInfo = mock(UriInfo.class);
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(requestContext.getMethod()).thenReturn("GET");
        when(requestContext.getHeaders()).thenReturn(new MultivaluedHashMap<>());

        SidecarConfig.AuthConfig authConfig = mock(SidecarConfig.AuthConfig.class);
        when(config.auth()).thenReturn(authConfig);
        when(authConfig.enabled()).thenReturn(true);
        when(authConfig.publicPaths()).thenReturn(List.of("/public/*"));
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testFilter_InternalPath() {
        when(uriInfo.getPath()).thenReturn("/q/health");
        Response response = filter.filter(requestContext).await().indefinitely();
        assertNull(response); // returns Uni.createFrom().nullItem() for skipped auth
    }

    @Test
    void testFilter_PublicPath_NoRequestId() {
        when(uriInfo.getPath()).thenReturn("/public/info");
        when(requestContext.getHeaderString("X-Request-ID")).thenReturn(null);
        Response response = filter.filter(requestContext).await().indefinitely();
        assertNull(response);
        verify(requestContext).setProperty(eq("X-Request-ID"), anyString()); // ensures uuid was generated
    }

    @Test
    void testFilter_AuthzDisabled() {
        when(uriInfo.getPath()).thenReturn("/api/secured");
        when(requestContext.getHeaderString("X-Request-ID")).thenReturn("req-123");

        SidecarConfig.AuthzConfig authzConfig = mock(SidecarConfig.AuthzConfig.class);
        when(config.authz()).thenReturn(authzConfig);
        when(authzConfig.enabled()).thenReturn(false); // AuthZ explicitly disabled

        AuthContext ctx = AuthContext.builder().userId("u1").build();
        when(authService.extractAuthContext(any())).thenReturn(ctx);
        when(rolesService.enrichWithRoles(ctx)).thenReturn(Uni.createFrom().item(ctx));

        Response response = filter.filter(requestContext).await().indefinitely();
        assertNull(response);
    }

    @Test
    void testFilter_AuthzDenied_ForbiddenResponse() {
        when(uriInfo.getPath()).thenReturn("/api/secured");
        when(requestContext.getHeaderString("X-Request-ID")).thenReturn("req-123");

        SidecarConfig.AuthzConfig authzConfig = mock(SidecarConfig.AuthzConfig.class);
        when(config.authz()).thenReturn(authzConfig);
        when(authzConfig.enabled()).thenReturn(true);

        AuthContext ctx = AuthContext.builder().userId("u1").build();
        when(authService.extractAuthContext(any())).thenReturn(ctx);
        when(rolesService.enrichWithRoles(ctx)).thenReturn(Uni.createFrom().item(ctx));

        // Mock the Uri building for extractQueryParams
        try {
            when(uriInfo.getRequestUri()).thenReturn(new URI("http://host/api/secured"));
        } catch (Exception ignored) {
        }

        when(policyService.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(PolicyDecision.deny("Not allowed", List.of("vio1"))));

        Response response = filter.filter(requestContext).await().indefinitely();
        assertNotNull(response);
        assertEquals(403, response.getStatus());

        AuthProxyFilter.ErrorResponse err = (AuthProxyFilter.ErrorResponse) response.getEntity();
        assertEquals("forbidden", err.code());
        assertEquals("Not allowed", err.message());
        assertEquals(List.of("vio1"), err.details());
    }

    @Test
    void testFilter_UnexpectedError_InternalServerResponse() {
        when(uriInfo.getPath()).thenReturn("/api/secured");
        when(requestContext.getHeaderString("X-Request-ID")).thenReturn("req-123");

        SidecarConfig.AuthzConfig authzConfig = mock(SidecarConfig.AuthzConfig.class);
        when(config.authz()).thenReturn(authzConfig);
        when(authzConfig.enabled()).thenReturn(true);

        AuthContext ctx = AuthContext.builder().userId("u1").build();
        when(authService.extractAuthContext(any())).thenReturn(ctx);

        // Inject an unexpected failure
        when(rolesService.enrichWithRoles(ctx))
                .thenReturn(Uni.createFrom().failure(new IllegalStateException("Kaboom")));

        Response response = filter.filter(requestContext).await().indefinitely();
        assertNotNull(response);
        assertEquals(500, response.getStatus());
        AuthProxyFilter.ErrorResponse err = (AuthProxyFilter.ErrorResponse) response.getEntity();
        assertEquals("error", err.code());
        assertEquals("Internal server error", err.message());
    }
}
