package space.maatini.sidecar.filter;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import space.maatini.sidecar.model.RolesResponse;
import space.maatini.sidecar.service.AuthenticationService;
import space.maatini.sidecar.service.PolicyService;
import space.maatini.sidecar.service.ProxyService;
import space.maatini.sidecar.service.RolesService;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthProxyFilterPojoTest {

    private AuthProxyFilter filter;
    private SidecarConfig config;
    private SecurityIdentity securityIdentity;
    private AuthenticationService authService;
    private PolicyService policyService;
    private ProxyService proxyService;
    private RolesService rolesService;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setup() throws Exception {
        filter = new AuthProxyFilter();
        config = mock(SidecarConfig.class);
        securityIdentity = mock(SecurityIdentity.class);
        authService = mock(AuthenticationService.class);
        policyService = mock(PolicyService.class);
        proxyService = mock(ProxyService.class);
        rolesService = mock(RolesService.class);
        meterRegistry = new SimpleMeterRegistry();

        setField(filter, "config", config);
        setField(filter, "securityIdentity", securityIdentity);
        setField(filter, "authenticationService", authService);
        setField(filter, "policyService", policyService);
        setField(filter, "proxyService", proxyService);
        setField(filter, "rolesService", rolesService);
        setField(filter, "meterRegistry", meterRegistry);

        // Common config
        SidecarConfig.AuthConfig authConfig = mock(SidecarConfig.AuthConfig.class);
        SidecarConfig.AuthzConfig authzConfig = mock(SidecarConfig.AuthzConfig.class);
        when(config.auth()).thenReturn(authConfig);
        when(config.authz()).thenReturn(authzConfig);
        when(authConfig.enabled()).thenReturn(true);
        when(authzConfig.enabled()).thenReturn(true);
        when(authConfig.publicPaths()).thenReturn(Collections.emptyList());

        filter.init();
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testFilter_InternalPath() {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/q/health");

        Response res = filter.filter(context).await().indefinitely();
        assertNull(res);
        verify(authService, never()).extractAuthContext(any(SecurityIdentity.class));
    }

    @Test
    void testFilter_PublicPath() {
        when(config.auth().publicPaths()).thenReturn(List.of("/health"));
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/health");

        Response res = filter.filter(context).await().indefinitely();
        assertNull(res);
        verify(authService, never()).extractAuthContext(any(SecurityIdentity.class));
    }

    @Test
    void testFilter_AuthFailure() {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/data");
        when(context.getMethod()).thenReturn("GET");

        when(authService.extractAuthContext(eq(securityIdentity)))
                .thenReturn(Uni.createFrom().item(AuthContext.anonymous()));
        when(rolesService.enrich(any(AuthContext.class)))
                .thenAnswer(inv -> Uni.createFrom().item((AuthContext) inv.getArgument(0)));

        Response res = filter.filter(context).await().indefinitely();
        assertNotNull(res);
        assertEquals(401, res.getStatus());
    }

    @Test
    void testFilter_Success() {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/data");
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(context.getMethod()).thenReturn("GET");
        when(context.getHeaders()).thenReturn(new MultivaluedHashMap<>());

        AuthContext authCtx = AuthContext.builder().userId("u123").email("u@u").build();
        when(authService.extractAuthContext(eq(securityIdentity))).thenReturn(Uni.createFrom().item(authCtx));
        when(rolesService.enrich(any(AuthContext.class)))
                .thenAnswer(inv -> Uni.createFrom().item((AuthContext) inv.getArgument(0)));
        when(policyService.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(PolicyDecision.allow()));

        Response res = filter.filter(context).await().indefinitely();
        assertNull(res, "Success should return null to continue the filter chain");
        verify(context).setProperty("auth.context", authCtx);
    }

    @Test
    void testFilter_Forbidden() {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/data");
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(context.getMethod()).thenReturn("GET");
        when(context.getHeaders()).thenReturn(new MultivaluedHashMap<>());

        AuthContext authCtx = AuthContext.builder().userId("u123").email("u@u").build();
        when(authService.extractAuthContext(eq(securityIdentity))).thenReturn(Uni.createFrom().item(authCtx));
        when(rolesService.enrich(any(AuthContext.class)))
                .thenAnswer(inv -> Uni.createFrom().item((AuthContext) inv.getArgument(0)));
        when(policyService.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(PolicyDecision.deny("Denied by test")));

        Response res = filter.filter(context).await().indefinitely();
        assertNotNull(res);
        assertEquals(403, res.getStatus());
    }
}
