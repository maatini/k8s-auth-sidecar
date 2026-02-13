package space.maatini.sidecar.filter;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.model.PolicyDecision;
import space.maatini.sidecar.service.AuthenticationService;
import space.maatini.sidecar.service.PolicyService;
import space.maatini.sidecar.service.RolesService;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestProfile(AuthProxyFilterTest.Profile.class)
class AuthProxyFilterTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "sidecar.auth.enabled", "true",
                    "sidecar.authz.enabled", "true",
                    "sidecar.auth.public-paths[0]", "/public/**");
        }
    }

    @Inject
    AuthProxyFilter authProxyFilter;

    @Inject
    SidecarConfig config;

    @InjectMock
    AuthenticationService authenticationService;

    @InjectMock
    RolesService rolesService;

    @InjectMock
    PolicyService policyService;

    @InjectMock
    SecurityIdentity securityIdentity;

    @Test
    void testFilter_InternalPath_Skipped() throws IOException {
        ContainerRequestContext req = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(req.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/q/health");

        authProxyFilter.filter(req);

        verify(authenticationService, never()).extractAuthContext(any());
    }

    @Test
    void testFilter_PublicPath_Skipped() throws IOException {
        ContainerRequestContext req = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(req.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/public/test");

        authProxyFilter.filter(req);

        verify(authenticationService, never()).extractAuthContext(any());
    }

    @Test
    void testFilter_Unauthenticated_Aborted() throws IOException {
        ContainerRequestContext req = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(req.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/data");
        when(req.getMethod()).thenReturn("GET");

        when(authenticationService.extractAuthContext(any())).thenReturn(AuthContext.anonymous());

        authProxyFilter.filter(req);

        verify(req).abortWith(argThat(r -> r.getStatus() == 401));
    }

    @Test
    void testFilter_AuthorizationDenied_Aborted() throws IOException {
        ContainerRequestContext req = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(req.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/admin");
        when(req.getMethod()).thenReturn("DELETE");
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost/api/admin"));

        AuthContext context = AuthContext.builder().userId("user1").build();
        when(authenticationService.extractAuthContext(any())).thenReturn(context);
        when(rolesService.enrichWithRoles(any())).thenReturn(Uni.createFrom().item(context));
        when(req.getHeaders()).thenReturn(mock(jakarta.ws.rs.core.MultivaluedMap.class));
        when(req.getUriInfo().getQueryParameters()).thenReturn(mock(jakarta.ws.rs.core.MultivaluedMap.class));

        PolicyDecision denial = PolicyDecision.deny("Not an admin");
        when(policyService.evaluate(any(), any(), any(), any(), any())).thenReturn(Uni.createFrom().item(denial));

        authProxyFilter.filter(req);

        verify(req).abortWith(argThat(r -> r.getStatus() == 403));
    }
}
