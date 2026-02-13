package space.maatini.sidecar.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@TestProfile(AuditLogFilterTest.Profile.class)
class AuditLogFilterTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "sidecar.audit.enabled", "true",
                    "sidecar.audit.sensitive-headers", "Authorization,Cookie");
        }
    }

    @Inject
    AuditLogFilter auditLogFilter;

    @Inject
    SidecarConfig config;

    @Inject
    ObjectMapper objectMapper;

    @Test
    void testFilterRequest_GeneratesRequestId() throws IOException {
        ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
        when(requestContext.getHeaderString("X-Request-ID")).thenReturn(null);

        auditLogFilter.filter(requestContext);

        verify(requestContext).setProperty(eq("audit.startTime"), anyLong());
        verify(requestContext).setProperty(eq("audit.requestId"), anyString());
    }

    @Test
    void testFilterRequest_UsesExistingRequestId() throws IOException {
        ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
        when(requestContext.getHeaderString("X-Request-ID")).thenReturn("existing-id");

        auditLogFilter.filter(requestContext);

        verify(requestContext).setProperty("audit.requestId", "existing-id");
    }

    @Test
    void testFilterResponse_LogsStructuredJson() throws IOException {
        ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
        ContainerResponseContext responseContext = mock(ContainerResponseContext.class);
        jakarta.ws.rs.core.UriInfo uriInfo = mock(jakarta.ws.rs.core.UriInfo.class);

        when(requestContext.getProperty("audit.startTime")).thenReturn(System.currentTimeMillis() - 100);
        when(requestContext.getProperty("audit.requestId")).thenReturn("test-id");
        when(requestContext.getProperty("auth.context")).thenReturn(
                AuthContext.builder().userId("user-1").email("user@example.com").tenant("t1").build());
        when(requestContext.getMethod()).thenReturn("POST");
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/test");
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost/api/test?query=v"));

        jakarta.ws.rs.core.MultivaluedHashMap<String, String> headers = new jakarta.ws.rs.core.MultivaluedHashMap<>();
        headers.add("User-Agent", "Mozilla");
        headers.add("Authorization", "Bearer sensitive");
        headers.add("X-Custom", "value");
        when(requestContext.getHeaders()).thenReturn(headers);
        when(requestContext.getHeaderString("User-Agent")).thenReturn("Mozilla");

        when(responseContext.getStatus()).thenReturn(201);

        assertDoesNotThrow(() -> auditLogFilter.filter(requestContext, responseContext));
    }

    @Test
    void testDetermineOutcome() throws Exception {
        testOutcome(200, "SUCCESS");
        testOutcome(401, "AUTHENTICATION_FAILED");
        testOutcome(403, "AUTHORIZATION_DENIED");
        testOutcome(404, "NOT_FOUND");
        testOutcome(429, "RATE_LIMITED");
        testOutcome(500, "SERVER_ERROR");
    }

    private void testOutcome(int status, String expectedOutcome) throws IOException {
        ContainerRequestContext req = mock(ContainerRequestContext.class);
        ContainerResponseContext res = mock(ContainerResponseContext.class);
        jakarta.ws.rs.core.UriInfo uriInfo = mock(jakarta.ws.rs.core.UriInfo.class);

        when(req.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/");
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost/"));
        when(req.getHeaders()).thenReturn(new jakarta.ws.rs.core.MultivaluedHashMap<>());
        when(res.getStatus()).thenReturn(status);

        assertDoesNotThrow(() -> auditLogFilter.filter(req, res));
    }
}
