package space.maatini.sidecar.web.filter;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.common.model.AuthContext;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestProfile(AuditLogFilterTest.Profile.class)
class AuditLogFilterTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("sidecar.audit.enabled", "true");
        }
    }

    @Inject
    AuditLogFilter auditLogFilter;

    @Test
    void testFilterRequest() throws IOException {
        ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
        auditLogFilter.filterRequest(requestContext);
        verify(requestContext).setProperty(eq("audit.start_time"), anyLong());
    }

    @Test
    void testFilterResponse_Success() throws IOException {
        ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
        ContainerResponseContext responseContext = mock(ContainerResponseContext.class);
        jakarta.ws.rs.core.UriInfo uriInfo = mock(jakarta.ws.rs.core.UriInfo.class);

        when(requestContext.getProperty("audit.start_time")).thenReturn(System.currentTimeMillis() - 100);
        when(requestContext.getProperty("auth.context")).thenReturn(
                AuthContext.builder().userId("user-1").email("user@example.com").roles(Set.of("r1")).build());
        when(requestContext.getMethod()).thenReturn("POST");
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/test");
        when(responseContext.getStatus()).thenReturn(200);

        assertDoesNotThrow(() -> auditLogFilter.filterResponse(requestContext, responseContext));
    }

    @Test
    void testFilterResponse_WithoutAuth() throws IOException {
        ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
        ContainerResponseContext responseContext = mock(ContainerResponseContext.class);
        jakarta.ws.rs.core.UriInfo uriInfo = mock(jakarta.ws.rs.core.UriInfo.class);

        when(requestContext.getProperty("audit.start_time")).thenReturn(System.currentTimeMillis());
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/test");
        when(requestContext.getMethod()).thenReturn("GET");
        when(responseContext.getStatus()).thenReturn(200);

        assertDoesNotThrow(() -> auditLogFilter.filterResponse(requestContext, responseContext));
    }
}

@QuarkusTest
@TestProfile(AuditLogFilterDisabledTest.DisabledProfile.class)
class AuditLogFilterDisabledTest {
    public static class DisabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("sidecar.audit.enabled", "false");
        }
    }

    @Inject
    AuditLogFilter auditLogFilter;

    @Test
    void testFilter_Disabled() throws IOException {
        ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
        auditLogFilter.filterRequest(requestContext);
        verify(requestContext, never()).setProperty(anyString(), any());
    }
}
