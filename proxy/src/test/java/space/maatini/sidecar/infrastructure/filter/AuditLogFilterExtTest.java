package space.maatini.sidecar.infrastructure.filter;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;

import static org.mockito.Mockito.*;

class AuditLogFilterExtTest {

    private AuditLogFilter filter;
    private SidecarConfig config;
    private SidecarConfig.AuditConfig auditConfig;

    @BeforeEach
    void setUp() {
        filter = new AuditLogFilter();
        config = mock(SidecarConfig.class);
        auditConfig = mock(SidecarConfig.AuditConfig.class);
        when(config.audit()).thenReturn(auditConfig);
        filter.config = config;
    }

    @Test
    void testFilterResponse_Disabled() {
        when(auditConfig.enabled()).thenReturn(false);
        ContainerRequestContext req = mock(ContainerRequestContext.class);
        ContainerResponseContext res = mock(ContainerResponseContext.class);
        
        filter.filterResponse(req, res);
        
        // Assert property wasn't even read
        verify(req, never()).getProperty(anyString());
    }

    @Test
    void testFilterRequest_Disabled() {
        when(auditConfig.enabled()).thenReturn(false);
        ContainerRequestContext req = mock(ContainerRequestContext.class);
        
        filter.filterRequest(req);
        
        // Assert start time not set
        verify(req, never()).setProperty(anyString(), any());
    }
}
