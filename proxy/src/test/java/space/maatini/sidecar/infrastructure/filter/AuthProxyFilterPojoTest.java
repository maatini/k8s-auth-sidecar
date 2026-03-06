package space.maatini.sidecar.infrastructure.filter;
 
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.application.service.SidecarRequestProcessor;
import space.maatini.sidecar.domain.model.ProcessingResult;
import space.maatini.sidecar.domain.model.SidecarRequest;
 
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
 
class AuthProxyFilterPojoTest {
    private AuthProxyFilter filter;
    private SidecarRequestProcessor processor;
 
    @BeforeEach
    void setup() {
        filter = new AuthProxyFilter();
        processor = mock(SidecarRequestProcessor.class);
        filter.processor = processor;
    }
 
    @Test
    void testFilter_Proceed() {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/data");
        when(context.getMethod()).thenReturn("GET");
        when(context.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
 
        when(processor.process(any(SidecarRequest.class))).thenReturn(Uni.createFrom().item(ProcessingResult.skip()));
        Response res = filter.filter(context).await().indefinitely();
        assertNull(res);
    }
 
    @Test
    void testFilter_Unauthenticated_Returns401() {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/data");
        when(context.getMethod()).thenReturn("GET");
        when(context.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
 
        when(processor.process(any(SidecarRequest.class)))
                .thenReturn(Uni.createFrom().item(ProcessingResult.unauthorized("Authentication required")));
 
        Response res = filter.filter(context).await().indefinitely();
        assertNotNull(res);
        assertEquals(401, res.getStatus());
    }
 
    @Test
    void testFilter_Error() {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/data");
        when(context.getMethod()).thenReturn("GET");
        when(context.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
 
        when(processor.process(any(SidecarRequest.class))).thenReturn(Uni.createFrom().item(ProcessingResult.error("Boom")));
 
        Response res = filter.filter(context).await().indefinitely();
        assertNotNull(res);
        assertEquals(500, res.getStatus());
    }
}
