package space.maatini.sidecar.filter;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;

import java.lang.reflect.Field;

import static org.mockito.Mockito.*;

class AuditLogFilterPojoTest {

    private AuditLogFilter filter;
    private SidecarConfig config;

    @BeforeEach
    void setup() throws Exception {
        filter = new AuditLogFilter();
        config = mock(SidecarConfig.class);

        setField(filter, "config", config);

        SidecarConfig.AuditConfig audit = mock(SidecarConfig.AuditConfig.class);
        when(config.audit()).thenReturn(audit);
        when(audit.enabled()).thenReturn(true);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testFilterResponse_FullAudit() {
        ContainerRequestContext req = mock(ContainerRequestContext.class);
        ContainerResponseContext res = mock(ContainerResponseContext.class);
        UriInfo uriInfo = mock(UriInfo.class);

        when(req.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/test");
        when(req.getMethod()).thenReturn("GET");
        when(res.getStatus()).thenReturn(200);
        when(req.getProperty("audit.start_time")).thenReturn(System.currentTimeMillis() - 150);

        AuthContext auth = AuthContext.builder().userId("user123").build();
        when(req.getProperty("auth.context")).thenReturn(auth);

        filter.filterResponse(req, res);

        verify(req).getProperty("audit.start_time");
        verify(res).getStatus();
    }

    @Test
    void testFilterResponse_NoAuthContext_ErrorStatus() {
        ContainerRequestContext req = mock(ContainerRequestContext.class);
        ContainerResponseContext res = mock(ContainerResponseContext.class);
        UriInfo uriInfo = mock(UriInfo.class);

        when(req.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/secret");
        when(req.getMethod()).thenReturn("POST");
        when(res.getStatus()).thenReturn(401);
        when(req.getProperty("audit.start_time")).thenReturn(null); // Missing start time
        // No AuthContext injected

        filter.filterResponse(req, res);

        verify(res).getStatus();
    }
}
