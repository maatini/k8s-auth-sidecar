package space.maatini.sidecar.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AuditLogFilterPojoTest {

    private AuditLogFilter filter;
    private SidecarConfig config;
    private ObjectMapper objectMapper;
    private ContainerRequestContext reqContext;
    private ContainerResponseContext resContext;
    private UriInfo uriInfo;

    @BeforeEach
    void setup() throws Exception {
        filter = new AuditLogFilter();
        config = mock(SidecarConfig.class);
        objectMapper = mock(ObjectMapper.class);

        SidecarConfig.AuditConfig auditConfig = mock(SidecarConfig.AuditConfig.class);
        when(config.audit()).thenReturn(auditConfig);
        when(auditConfig.enabled()).thenReturn(true);
        when(auditConfig.sensitiveHeaders()).thenReturn(List.of("Authorization"));

        setField(filter, "config", config);
        setField(filter, "objectMapper", objectMapper);

        reqContext = mock(ContainerRequestContext.class);
        resContext = mock(ContainerResponseContext.class);
        uriInfo = mock(UriInfo.class);

        when(reqContext.getUriInfo()).thenReturn(uriInfo);
        when(reqContext.getMethod()).thenReturn("GET");
        when(uriInfo.getPath()).thenReturn("/api/test");
        when(uriInfo.getRequestUri()).thenReturn(new URI("http://host/api/test?query=true"));
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testFilterIn_Disabled() throws Exception {
        when(config.audit().enabled()).thenReturn(false);
        filter.filter(reqContext);
        verify(reqContext, never()).setProperty(anyString(), any());
    }

    @Test
    void testFilterIn_Enabled() throws Exception {
        when(reqContext.getHeaderString("X-Request-ID")).thenReturn("existing-id");
        filter.filter(reqContext);
        verify(reqContext).setProperty(eq("audit.startTime"), anyLong());
        verify(reqContext).setProperty("audit.requestId", "existing-id");
    }

    @Test
    void testFilterIn_GenerateRequestId() throws Exception {
        when(reqContext.getHeaderString("X-Request-ID")).thenReturn(null);
        filter.filter(reqContext);
        verify(reqContext).setProperty(eq("audit.startTime"), anyLong());
        verify(reqContext).setProperty(eq("audit.requestId"), argThat(arg -> arg != null && !((String) arg).isEmpty()));
    }

    @Test
    void testFilterOut_Disabled() throws Exception {
        when(config.audit().enabled()).thenReturn(false);
        filter.filter(reqContext, resContext);
        verify(objectMapper, never()).writeValueAsString(any());
    }

    @Test
    void testFilterOut_Success() throws Exception {
        // Setup req context
        when(reqContext.getProperty("audit.startTime")).thenReturn(System.currentTimeMillis() - 100);
        when(reqContext.getProperty("audit.requestId")).thenReturn("req-123");
        when(reqContext.getProperty("auth.context"))
                .thenReturn(AuthContext.builder().userId("u1").email("email@o.com").tenant("t1").build());

        MultivaluedHashMap<String, String> headers = new MultivaluedHashMap<>();
        headers.put("Authorization", List.of("Bearer abc"));
        headers.put("aUtHoRiZaTiOn", List.of("Bearer case-insensitive"));
        headers.put("Accept", List.of("application/json"));
        headers.put("EmptyHeader", List.of());
        when(reqContext.getHeaders()).thenReturn(headers);

        when(reqContext.getHeaderString("X-Forwarded-For")).thenReturn("10.0.0.1, 10.0.0.5");
        when(reqContext.getHeaderString("User-Agent")).thenReturn("Mozilla");

        // Setup res context
        when(resContext.getStatus()).thenReturn(200);

        filter.filter(reqContext, resContext);

        org.mockito.ArgumentCaptor<AuditLogFilter.AuditLogEntry> captor = org.mockito.ArgumentCaptor
                .forClass(AuditLogFilter.AuditLogEntry.class);
        verify(objectMapper).writeValueAsString(captor.capture());

        AuditLogFilter.AuditLogEntry entry = captor.getValue();
        assertEquals("req-123", entry.requestId());
        assertEquals("request", entry.eventType());
        assertEquals("SUCCESS", entry.outcome());
        assertNotNull(entry.timestamp());

        assertEquals("u1", entry.user().id());
        assertEquals("email@o.com", entry.user().email());
        assertEquals("t1", entry.user().tenant());

        assertEquals("GET", entry.request().method());
        assertEquals("/api/test", entry.request().path());
        assertEquals("query=true", entry.request().queryString());
        assertEquals("10.0.0.1", entry.request().remoteAddress());
        assertEquals("Mozilla", entry.request().userAgent());
        assertEquals("[REDACTED]", entry.request().headers().get("Authorization"));
        assertEquals("[REDACTED]", entry.request().headers().get("aUtHoRiZaTiOn"));
        assertEquals("application/json", entry.request().headers().get("Accept"));
        assertEquals("", entry.request().headers().get("EmptyHeader"));

        assertEquals(200, entry.response().statusCode());
        assertEquals("SUCCESS", entry.response().statusFamily());
        assertTrue(entry.response().durationMs() >= 100);
    }

    @Test
    void testFilterOut_ExceptionsAndStatus() throws Exception {
        when(reqContext.getProperty("audit.startTime")).thenReturn(null);
        when(reqContext.getHeaders()).thenReturn(new MultivaluedHashMap<>());

        when(reqContext.getHeaderString("X-Forwarded-For")).thenReturn(null);
        when(reqContext.getHeaderString("X-Real-IP")).thenReturn("192.168.1.50");

        org.mockito.ArgumentCaptor<AuditLogFilter.AuditLogEntry> captor = org.mockito.ArgumentCaptor
                .forClass(AuditLogFilter.AuditLogEntry.class);

        // Status variations
        when(resContext.getStatus()).thenReturn(404);
        filter.filter(reqContext, resContext);

        when(resContext.getStatus()).thenReturn(401);
        filter.filter(reqContext, resContext);

        when(resContext.getStatus()).thenReturn(403);
        filter.filter(reqContext, resContext);

        when(resContext.getStatus()).thenReturn(429);
        filter.filter(reqContext, resContext);

        when(resContext.getStatus()).thenReturn(500);
        filter.filter(reqContext, resContext);

        when(resContext.getStatus()).thenReturn(302);
        filter.filter(reqContext, resContext);

        when(resContext.getStatus()).thenReturn(100);
        filter.filter(reqContext, resContext);

        when(resContext.getStatus()).thenReturn(600); // Unknown
        filter.filter(reqContext, resContext);

        verify(objectMapper, times(8)).writeValueAsString(captor.capture());

        List<AuditLogFilter.AuditLogEntry> entries = captor.getAllValues();
        assertEquals("NOT_FOUND", entries.get(0).outcome());
        assertEquals("CLIENT_ERROR", entries.get(0).response().statusFamily());

        assertEquals("AUTHENTICATION_FAILED", entries.get(1).outcome());
        assertEquals("AUTHORIZATION_DENIED", entries.get(2).outcome());
        assertEquals("RATE_LIMITED", entries.get(3).outcome());

        assertEquals("SERVER_ERROR", entries.get(4).outcome());
        assertEquals("SERVER_ERROR", entries.get(4).response().statusFamily());

        assertEquals("UNKNOWN", entries.get(5).outcome());
        assertEquals("REDIRECTION", entries.get(5).response().statusFamily());

        assertEquals("UNKNOWN", entries.get(6).outcome());
        assertEquals("INFORMATIONAL", entries.get(6).response().statusFamily());

        assertEquals("SERVER_ERROR", entries.get(7).outcome());
        assertEquals("UNKNOWN", entries.get(7).response().statusFamily());

        for (AuditLogFilter.AuditLogEntry entry : entries) {
            assertEquals("anonymous", entry.user().id());
            assertEquals(0L, entry.response().durationMs());
            assertEquals("192.168.1.50", entry.request().remoteAddress());
        }
    }

    @Test
    void testFilterOut_NoRealIp_UnknownAddress() throws Exception {
        when(reqContext.getProperty("audit.startTime")).thenReturn(null);
        when(reqContext.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(reqContext.getHeaderString("X-Forwarded-For")).thenReturn(null);
        when(reqContext.getHeaderString("X-Real-IP")).thenReturn("");
        when(resContext.getStatus()).thenReturn(200);

        filter.filter(reqContext, resContext);
        org.mockito.ArgumentCaptor<AuditLogFilter.AuditLogEntry> captor = org.mockito.ArgumentCaptor
                .forClass(AuditLogFilter.AuditLogEntry.class);
        verify(objectMapper).writeValueAsString(captor.capture());
        assertEquals("unknown", captor.getValue().request().remoteAddress());
    }

    @Test
    void testFilterOut_WriteException() throws Exception {
        when(reqContext.getProperty("audit.startTime")).thenReturn(System.currentTimeMillis());
        when(reqContext.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(resContext.getStatus()).thenReturn(200);

        when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("JSON Parse Fail"));
        filter.filter(reqContext, resContext);
    }
}
