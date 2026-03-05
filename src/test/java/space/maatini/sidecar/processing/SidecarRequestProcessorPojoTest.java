package space.maatini.sidecar.processing;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.authn.AuthenticationService;
import space.maatini.sidecar.common.config.SidecarConfig;
import space.maatini.sidecar.common.model.AuthContext;
import space.maatini.sidecar.common.model.PolicyDecision;
import space.maatini.sidecar.policy.PolicyService;
import space.maatini.sidecar.roles.RolesService;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SidecarRequestProcessorPojoTest {

    private SidecarRequestProcessor processor;
    private SidecarConfig config;
    private SecurityIdentity securityIdentity;
    private AuthenticationService authService;
    private PolicyService policyService;
    private RolesService rolesService;

    @BeforeEach
    void setup() {
        processor = new SidecarRequestProcessor();
        config = mock(SidecarConfig.class);
        securityIdentity = mock(SecurityIdentity.class);
        authService = mock(AuthenticationService.class);
        policyService = mock(PolicyService.class);
        rolesService = mock(RolesService.class);

        processor.config = config;
        processor.securityIdentity = securityIdentity;
        processor.authenticationService = authService;
        processor.policyService = policyService;
        processor.rolesService = rolesService;

        // Common config
        SidecarConfig.AuthConfig authConfig = mock(SidecarConfig.AuthConfig.class);
        SidecarConfig.AuthzConfig authzConfig = mock(SidecarConfig.AuthzConfig.class);
        when(config.auth()).thenReturn(authConfig);
        when(config.authz()).thenReturn(authzConfig);
        when(authConfig.enabled()).thenReturn(true);
        when(authzConfig.enabled()).thenReturn(true);
        when(authConfig.publicPaths()).thenReturn(Collections.emptyList());
    }

    @Test
    void testProcess_InternalPath() {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/q/health");

        ProcessingResult result = processor.process(context).await().indefinitely();
        assertTrue(result instanceof ProcessingResult.Skip);
        verify(authService, never()).extractAuthContext(any());
    }

    @Test
    void testProcess_PublicPath() {
        when(config.auth().publicPaths()).thenReturn(List.of("/health"));
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/health");

        ProcessingResult result = processor.process(context).await().indefinitely();
        assertTrue(result instanceof ProcessingResult.Skip);
        verify(authService, never()).extractAuthContext(any());
    }

    @Test
    void testProcess_AuthDisabled() {
        when(config.auth().enabled()).thenReturn(false);
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/data");

        ProcessingResult result = processor.process(context).await().indefinitely();
        assertTrue(result instanceof ProcessingResult.Proceed);
        assertEquals("anonymous", ((ProcessingResult.Proceed) result).authContext().userId());
    }

    @Test
    void testProcess_AuthFailure() {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/data");
        when(context.getMethod()).thenReturn("GET");

        when(authService.extractAuthContext(eq(securityIdentity)))
                .thenReturn(Uni.createFrom().item(AuthContext.anonymous()));

        ProcessingResult result = processor.process(context).await().indefinitely();
        assertTrue(result instanceof ProcessingResult.Unauthorized);
        assertEquals("Authentication required", ((ProcessingResult.Unauthorized) result).message());
    }

    @Test
    void testProcess_Success() {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/data");
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(context.getMethod()).thenReturn("GET");
        when(context.getHeaders()).thenReturn(new MultivaluedHashMap<>());

        AuthContext authCtx = AuthContext.builder().userId("u123").email("u@u").build();
        when(authService.extractAuthContext(eq(securityIdentity))).thenReturn(Uni.createFrom().item(authCtx));
        when(rolesService.enrich(any())).thenReturn(Uni.createFrom().item(authCtx));
        when(policyService.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(PolicyDecision.allow()));

        ProcessingResult result = processor.process(context).await().indefinitely();
        assertTrue(result instanceof ProcessingResult.Proceed);
        assertEquals("u123", ((ProcessingResult.Proceed) result).authContext().userId());
    }

    @Test
    void testProcess_Forbidden() {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/data");
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(context.getMethod()).thenReturn("GET");
        when(context.getHeaders()).thenReturn(new MultivaluedHashMap<>());

        AuthContext authCtx = AuthContext.builder().userId("u123").email("u@u").build();
        when(authService.extractAuthContext(eq(securityIdentity))).thenReturn(Uni.createFrom().item(authCtx));
        when(rolesService.enrich(any())).thenReturn(Uni.createFrom().item(authCtx));
        when(policyService.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(PolicyDecision.deny("Denied by test")));

        ProcessingResult result = processor.process(context).await().indefinitely();
        assertTrue(result instanceof ProcessingResult.Forbidden);
        assertEquals("Denied by test", ((ProcessingResult.Forbidden) result).decision().reason());
    }
}
