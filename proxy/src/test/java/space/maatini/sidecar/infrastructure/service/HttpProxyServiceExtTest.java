package space.maatini.sidecar.infrastructure.service;

import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.domain.model.ProxyResponse;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HttpProxyServiceExtTest {

    // ── ProxyResponse record tests ──

    @Test
    void testProxyResponseIsSuccess() {
        ProxyResponse r1 = new ProxyResponse(200, "OK", Map.of(), null);
        ProxyResponse r2 = new ProxyResponse(299, "OK", Map.of(), null);
        ProxyResponse r3 = new ProxyResponse(300, "OK", Map.of(), null);
        ProxyResponse r4 = new ProxyResponse(199, "OK", Map.of(), null);

        assertTrue(r1.isSuccess());
        assertTrue(r2.isSuccess());
        assertFalse(r3.isSuccess());
        assertFalse(r4.isSuccess());
    }

    @Test
    void testProxyResponseHeaders() {
        Map<String, String> h = Map.of("content-type", "application/json", "x-custom", "value");
        ProxyResponse resp = new ProxyResponse(200, "OK", h, Buffer.buffer("test"));

        assertEquals(2, resp.headers().size());
        assertEquals("test", resp.bodyAsString());

        // Null body → empty string via bodyAsString
        ProxyResponse respNull = new ProxyResponse(200, "OK", h, null);
        assertEquals("", respNull.bodyAsString());
    }

    @Test
    void testProxyResponse_ErrorSanitization() {
        ProxyResponse resp = ProxyResponse.error(500, "Error with \"quotes\"");
        assertTrue(resp.bodyAsString().contains("Error with \\\"quotes\\\""));

        ProxyResponse respNull = ProxyResponse.error(500, null);
        assertTrue(respNull.bodyAsString().contains("Internal error"));
    }

    // ── resolvePropagatedHeaders tests ──

    // Note: These private methods are now tested via reflection or by making them protected/accessible for testing if needed.
    // However, in this refactoring, I'll keep the tests as they were but targeting HttpProxyService.
    // I need to check if the original tests used direct access. They did (package-private).

    @Test
    void testResolvePropagatedHeaders_NullInput() throws Exception {
        HttpProxyService s = new HttpProxyService();
        java.lang.reflect.Method method = HttpProxyService.class.getDeclaredMethod("resolvePropagatedHeaders", Map.class);
        method.setAccessible(true);
        Map<?, ?> result = (Map<?, ?>) method.invoke(s, (Map<?, ?>) null);
        assertTrue(result.isEmpty());
    }

    @Test
    void testResolvePropagatedHeaders_EmptyList() throws Exception {
        HttpProxyService s = new HttpProxyService();
        s.config = mock(SidecarConfig.class);
        SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
        when(s.config.proxy()).thenReturn(pc);
        when(pc.propagateHeaders()).thenReturn(List.of());

        java.lang.reflect.Method method = HttpProxyService.class.getDeclaredMethod("resolvePropagatedHeaders", Map.class);
        method.setAccessible(true);
        Map<?, ?> r = (Map<?, ?>) method.invoke(s, Map.of("X-Trace-Id", "123"));
        assertTrue(r.isEmpty());
    }

    @Test
    void testResolvePropagatedHeaders_Full() throws Exception {
        HttpProxyService s = new HttpProxyService();
        s.config = mock(SidecarConfig.class);
        SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
        when(s.config.proxy()).thenReturn(pc);
        when(pc.propagateHeaders()).thenReturn(List.of("X-Trace-Id"));

        java.lang.reflect.Method method = HttpProxyService.class.getDeclaredMethod("resolvePropagatedHeaders", Map.class);
        method.setAccessible(true);

        Map<String, String> input = Map.of("x-trace-id", "trace-123", "x-not-propagated", "gone",
                "Content-Type", "application/json");
        Map<?, ?> r = (Map<?, ?>) method.invoke(s, input);

        assertEquals("trace-123", r.get("X-Trace-Id"));
        assertEquals("application/json", r.get("Content-Type"));
        assertNull(r.get("x-not-propagated"));
        assertEquals(2, r.size());
    }

    @Test
    void testBuildTargetUrl() {
        HttpProxyService s = new HttpProxyService();
        s.config = mock(SidecarConfig.class);
        SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
        SidecarConfig.ProxyConfig.TargetConfig tc = mock(SidecarConfig.ProxyConfig.TargetConfig.class);
        when(s.config.proxy()).thenReturn(pc);
        when(pc.target()).thenReturn(tc);
        when(tc.scheme()).thenReturn("https");
        when(tc.host()).thenReturn("example.com");
        when(tc.port()).thenReturn(443);

        String url = s.buildTargetUrl("/api/v1");
        assertEquals("https://example.com:443/api/v1", url);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_SendBranch() {
        HttpProxyService s = new HttpProxyService();
        s.requestCounter = mock(io.micrometer.core.instrument.Counter.class);
        s.errorCounter = mock(io.micrometer.core.instrument.Counter.class);
        s.requestTimer = mock(io.micrometer.core.instrument.Timer.class);
        s.webClient = mock(WebClient.class);
        s.config = mock(SidecarConfig.class);

        SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
        when(s.config.proxy()).thenReturn(pc);
        when(pc.propagateHeaders()).thenReturn(List.of("X-Prop"));
        when(pc.addHeaders()).thenReturn(Map.of("X-Added", "constant"));

        SidecarConfig.ProxyConfig.TimeoutConfig tc = mock(SidecarConfig.ProxyConfig.TimeoutConfig.class);
        when(pc.timeout()).thenReturn(tc);
        when(tc.read()).thenReturn(30000);

        SidecarConfig.ProxyConfig.TargetConfig tgt = mock(SidecarConfig.ProxyConfig.TargetConfig.class);
        when(pc.target()).thenReturn(tgt);
        when(tgt.scheme()).thenReturn("http");
        when(tgt.host()).thenReturn("localhost");
        when(tgt.port()).thenReturn(8081);

        HttpRequest<Buffer> req = mock(HttpRequest.class);
        when(s.webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(req);
        when(req.timeout(anyLong())).thenReturn(req);
        when(req.putHeader(anyString(), anyString())).thenReturn(req);
        when(req.addQueryParam(anyString(), anyString())).thenReturn(req);

        HttpResponse<Buffer> mockResp = mock(HttpResponse.class);
        when(mockResp.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.statusMessage()).thenReturn("OK");
        when(mockResp.body()).thenReturn(Buffer.buffer("ok"));
        when(req.send()).thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockResp));

        AuthContext ctx = AuthContext.builder().userId("user1").build();
        s.proxy("GET", "/test", Map.of("x-prop", "v"), Map.of("q", "1"), null, ctx)
                .subscribe().with(item -> {});

        verify(req, times(1)).send();
        verify(req, times(1)).putHeader("X-Prop", "v");
        verify(req, times(1)).putHeader("X-Added", "constant");
        verify(s.requestCounter, atLeastOnce()).increment();
    }
}
