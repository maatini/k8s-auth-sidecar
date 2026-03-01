package space.maatini.sidecar.resource;

import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.service.ProxyService;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@io.quarkus.test.junit.QuarkusTest
class ProxyResourceTest {

    ProxyResource resource;
    ProxyService proxyService;
    HttpHeaders headers;
    UriInfo uriInfo;
    io.vertx.core.http.HttpServerRequest request;
    ContainerRequestContext containerRequestContext;

    @BeforeEach
    void setUp() {
        resource = new ProxyResource();
        proxyService = Mockito.mock(ProxyService.class);
        headers = Mockito.mock(HttpHeaders.class);
        uriInfo = Mockito.mock(UriInfo.class);
        request = Mockito.mock(io.vertx.core.http.HttpServerRequest.class);
        containerRequestContext = Mockito.mock(ContainerRequestContext.class);

        resource.proxyService = proxyService;
        resource.headers = headers;
        resource.uriInfo = uriInfo;
        resource.request = request;
        resource.containerRequestContext = containerRequestContext;

        when(headers.getRequestHeaders()).thenReturn(Mockito.mock(jakarta.ws.rs.core.MultivaluedMap.class));
        when(uriInfo.getQueryParameters()).thenReturn(Mockito.mock(jakarta.ws.rs.core.MultivaluedMap.class));

        AuthContext ctx = AuthContext.anonymous();
        when(containerRequestContext.getProperty("auth.context")).thenReturn(ctx);

        ProxyService.ProxyResponse pr = new ProxyService.ProxyResponse(200, "OK", Map.of("Test-Header", "Value"),
                io.vertx.mutiny.core.buffer.Buffer.buffer("resp"));
        when(proxyService.proxy(any(), any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(pr));
    }

    @Test
    void testProxyGet() {
        Response resp = resource.proxyGet("users").await().indefinitely();
        assertNotNull(resp);
        assertEquals(200, resp.getStatus());
    }

    @Test
    void testProxyPost() {
        Response resp = resource.proxyPost("users").await().indefinitely();
        assertEquals(200, resp.getStatus());
    }

    @Test
    void testProxyPut() {
        Response resp = resource.proxyPut("users").await().indefinitely();
        assertEquals(200, resp.getStatus());
    }

    @Test
    void testProxyPatch() {
        Response resp = resource.proxyPatch("users").await().indefinitely();
        assertEquals(200, resp.getStatus());
    }

    @Test
    void testProxyDelete() {
        Response resp = resource.proxyDelete("users").await().indefinitely();
        assertEquals(200, resp.getStatus());
    }

    @Test
    void testProxyOptions() {
        Response resp = resource.proxyOptions("users").await().indefinitely();
        assertEquals(200, resp.getStatus());
    }

    @Test
    void testProxyHead() {
        Response resp = resource.proxyHead("users").await().indefinitely();
        assertEquals(200, resp.getStatus());
    }

    @Test
    void testProxyFailure() {
        when(proxyService.proxy(any(), any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Simulated")));

        Response resp = resource.proxyGet("users").await().indefinitely();
        assertEquals(500, resp.getStatus());
    }
}
