package space.maatini.sidecar.web.filter;

import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.processing.ProcessingResult;
import space.maatini.sidecar.processing.SidecarRequestProcessor;
import static org.junit.jupiter.api.Assertions.*;
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
        when(processor.process(context)).thenReturn(Uni.createFrom().item(ProcessingResult.skip()));
        Response res = filter.filter(context).await().indefinitely();
        assertNull(res);
    }

    @Test
    void testFilter_Unauthenticated_Returns401() {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        when(processor.process(context))
                .thenReturn(Uni.createFrom().item(ProcessingResult.unauthorized("Authentication required")));
        Response res = filter.filter(context).await().indefinitely();
        assertNotNull(res);
        assertEquals(401, res.getStatus());
    }

    @Test
    void testFilter_Error() {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        when(processor.process(context)).thenReturn(Uni.createFrom().item(ProcessingResult.error("Boom")));

        Response res = filter.filter(context).await().indefinitely();
        assertNotNull(res);
        assertEquals(500, res.getStatus());
    }
}
