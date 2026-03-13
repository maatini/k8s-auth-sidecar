package space.maatini.sidecar.infrastructure.service;

import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.core.http.HttpClient;
import io.vertx.mutiny.core.http.HttpClientRequest;
import io.vertx.mutiny.core.http.HttpClientResponse;
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
        ProxyResponse r1 = new ProxyResponse(200, "OK", Map.of(), null, false);
        ProxyResponse r2 = new ProxyResponse(299, "OK", Map.of(), null, false);
        ProxyResponse r3 = new ProxyResponse(300, "OK", Map.of(), null, false);
        ProxyResponse r4 = new ProxyResponse(199, "OK", Map.of(), null, false);

        assertTrue(r1.isSuccess());
        assertTrue(r2.isSuccess());
        assertFalse(r3.isSuccess());
        assertFalse(r4.isSuccess());
    }

    @Test
    void testProxyResponseHeaders() {
        Map<String, String> h = Map.of("content-type", "application/json", "x-custom", "value");
        ProxyResponse resp = new ProxyResponse(200, "OK", h, Buffer.buffer("test"), false);

        assertEquals(2, resp.headers().size());
        assertEquals("test", resp.bodyAsString());

        // Null body → empty string via bodyAsString
        ProxyResponse respNull = new ProxyResponse(200, "OK", h, null, false);
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
        s.httpClient = mock(HttpClient.class);
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

        HttpClientRequest req = mock(HttpClientRequest.class);
        when(s.httpClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(io.smallrye.mutiny.Uni.createFrom().item(req));
        when(req.setTimeout(anyLong())).thenReturn(req);
        when(req.putHeader(anyString(), anyString())).thenReturn(req);

        HttpClientResponse mockResp = mock(HttpClientResponse.class);
        when(mockResp.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(mockResp.statusCode()).thenReturn(200);
        when(mockResp.statusMessage()).thenReturn("OK");
        when(req.send()).thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockResp));

        AuthContext ctx = AuthContext.builder().userId("user1").build();
        s.proxy("GET", "/test", Map.of("x-prop", "v"), Map.of("q", "1"), null, null, ctx)
                .await().indefinitely();

        verify(req, times(1)).send();
        verify(req, times(1)).putHeader("X-Prop", "v");
        verify(req, times(1)).putHeader("X-Added", "constant");
        verify(s.requestCounter, atLeastOnce()).increment();
    }

    @Test
    void testProxy_nullHeaders() {
        HttpProxyService s = new HttpProxyService();
        s.config = mock(SidecarConfig.class);
        s.httpClient = mock(HttpClient.class);
        s.requestCounter = mock(io.micrometer.core.instrument.Counter.class);
        s.errorCounter = mock(io.micrometer.core.instrument.Counter.class);
        s.requestTimer = mock(io.micrometer.core.instrument.Timer.class);

        SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
        when(s.config.proxy()).thenReturn(pc);
        when(pc.propagateHeaders()).thenReturn(List.of("X-Test"));

        SidecarConfig.ProxyConfig.TargetConfig tgt = mock(SidecarConfig.ProxyConfig.TargetConfig.class);
        when(pc.target()).thenReturn(tgt);
        when(tgt.host()).thenReturn("localhost");
        when(tgt.port()).thenReturn(8081);

        HttpClientRequest req = mock(HttpClientRequest.class);
        when(s.httpClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(io.smallrye.mutiny.Uni.createFrom().item(req));
        when(req.setTimeout(anyLong())).thenReturn(req);
        when(req.putHeader(anyString(), anyString())).thenReturn(req);

        HttpClientResponse mockResp = mock(HttpClientResponse.class);
        when(mockResp.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(mockResp.statusCode()).thenReturn(200);
        when(req.send()).thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockResp));

        AuthContext authContext = AuthContext.builder().userId("user1").build();

        s.proxy("GET", "/test", null, null, null, null, authContext).await().indefinitely();

        verify(req, never()).putHeader(eq("X-Test"), anyString());
    }

    @Test
    void testProxy_nullAuthContext() {
        HttpProxyService s = new HttpProxyService();
        s.config = mock(SidecarConfig.class);
        s.httpClient = mock(HttpClient.class);
        s.requestCounter = mock(io.micrometer.core.instrument.Counter.class);
        s.errorCounter = mock(io.micrometer.core.instrument.Counter.class);
        s.requestTimer = mock(io.micrometer.core.instrument.Timer.class);

        SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
        when(s.config.proxy()).thenReturn(pc);
        when(pc.addHeaders()).thenReturn(Map.of());

        SidecarConfig.ProxyConfig.TargetConfig tgt = mock(SidecarConfig.ProxyConfig.TargetConfig.class);
        when(pc.target()).thenReturn(tgt);
        when(tgt.host()).thenReturn("localhost");
        when(tgt.port()).thenReturn(8081);

        HttpClientRequest req = mock(HttpClientRequest.class);
        when(s.httpClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(io.smallrye.mutiny.Uni.createFrom().item(req));
        when(req.setTimeout(anyLong())).thenReturn(req);

        HttpClientResponse mockResp = mock(HttpClientResponse.class);
        when(mockResp.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(mockResp.statusCode()).thenReturn(200);
        when(req.send()).thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockResp));

        s.proxy("GET", "/test", null, null, null, null, null).await().indefinitely();

        verify(req, never()).putHeader(eq("X-Auth-User-Id"), anyString());
    }

    @Test
    void testProxy_timeoutFailure() {
        HttpProxyService s = new HttpProxyService();
        s.config = mock(SidecarConfig.class);
        s.httpClient = mock(HttpClient.class);
        s.requestCounter = mock(io.micrometer.core.instrument.Counter.class);
        s.errorCounter = mock(io.micrometer.core.instrument.Counter.class);
        s.requestTimer = mock(io.micrometer.core.instrument.Timer.class);

        SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
        when(s.config.proxy()).thenReturn(pc);

        SidecarConfig.ProxyConfig.TargetConfig tgt = mock(SidecarConfig.ProxyConfig.TargetConfig.class);
        when(pc.target()).thenReturn(tgt);
        when(tgt.host()).thenReturn("localhost");
        when(tgt.port()).thenReturn(8081);

        HttpClientRequest req = mock(HttpClientRequest.class);
        when(s.httpClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(io.smallrye.mutiny.Uni.createFrom().item(req));
        when(req.setTimeout(anyLong())).thenReturn(req);
        when(req.send()).thenReturn(io.smallrye.mutiny.Uni.createFrom().failure(new RuntimeException("Timeout")));

        AuthContext authContext = AuthContext.builder().userId("user1").build();

        ProxyResponse res = s.proxy("GET", "/test", null, null, null, null, authContext).await().indefinitely();

        assertEquals(503, res.statusCode());
        verify(s.errorCounter).increment();
    }

    @Test
    void testResolvePropagatedHeaders_caseInsensitiveContentType() throws Exception {
        HttpProxyService s = new HttpProxyService();
        s.config = mock(SidecarConfig.class);
        SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
        when(s.config.proxy()).thenReturn(pc);
        when(pc.propagateHeaders()).thenReturn(java.util.List.of());

        Map<String, String> headers = Map.of("content-type", "application/json");

        java.lang.reflect.Method m = HttpProxyService.class.getDeclaredMethod("resolvePropagatedHeaders", Map.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> res = (Map<String, String>) m.invoke(s, headers);

        assertEquals("application/json", res.get("Content-Type"));
        assertNull(res.get("content-type"));
    }

    @Test
    void testResolvePropagatedHeaders_propagateCaseInsensitiveFind() throws Exception {
        HttpProxyService s = new HttpProxyService();
        s.config = mock(SidecarConfig.class);
        SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
        when(s.config.proxy()).thenReturn(pc);
        when(pc.propagateHeaders()).thenReturn(java.util.List.of("X-Foo"));

        Map<String, String> headers = Map.of("x-foo", "value");

        java.lang.reflect.Method m = HttpProxyService.class.getDeclaredMethod("resolvePropagatedHeaders", Map.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> res = (Map<String, String>) m.invoke(s, headers);

        assertEquals("value", res.get("X-Foo"));
    }

    @Test
    void testResolvePropagatedHeaders_nullHeaderValue() throws Exception {
        HttpProxyService s = new HttpProxyService();
        s.config = mock(SidecarConfig.class);
        SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
        when(s.config.proxy()).thenReturn(pc);
        when(pc.propagateHeaders()).thenReturn(java.util.List.of("X-Bar"));

        Map<String, String> headers = new java.util.HashMap<>();
        headers.put("X-Bar", null);

        java.lang.reflect.Method m = HttpProxyService.class.getDeclaredMethod("resolvePropagatedHeaders", Map.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> res = (Map<String, String>) m.invoke(s, headers);

        assertNull(res.get("X-Bar"));
    }

    @Test
    void testResolveAuthContextHeaders_nullAuth() throws Exception {
        HttpProxyService s = new HttpProxyService();
        java.lang.reflect.Method m = HttpProxyService.class.getDeclaredMethod("resolveAuthContextHeaders", AuthContext.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> res = (Map<String, String>) m.invoke(s, (AuthContext) null);

        assertTrue(res.isEmpty());
    }

    @Test
    void testResolveAuthContextHeaders_notAuthenticated() throws Exception {
        HttpProxyService s = new HttpProxyService();
        AuthContext auth = AuthContext.anonymous();

        java.lang.reflect.Method m = HttpProxyService.class.getDeclaredMethod("resolveAuthContextHeaders", AuthContext.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> res = (Map<String, String>) m.invoke(s, auth);

        assertTrue(res.isEmpty());
    }

    @Test
    void testResolvePlaceholders_nullFields() throws Exception {
        HttpProxyService s = new HttpProxyService();
        s.config = mock(SidecarConfig.class);
        s.httpClient = mock(HttpClient.class);
        s.requestCounter = mock(io.micrometer.core.instrument.Counter.class);
        s.errorCounter = mock(io.micrometer.core.instrument.Counter.class);
        s.requestTimer = mock(io.micrometer.core.instrument.Timer.class);

        SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
        when(s.config.proxy()).thenReturn(pc);
        when(pc.addHeaders()).thenReturn(Map.of("X-Test", "${user.id}-${user.email}-${user.name}-${user.roles}"));
        SidecarConfig.ProxyConfig.TimeoutConfig tc = mock(SidecarConfig.ProxyConfig.TimeoutConfig.class);
        when(pc.timeout()).thenReturn(tc);
        when(tc.read()).thenReturn(30000);

        SidecarConfig.ProxyConfig.TargetConfig tgt = mock(SidecarConfig.ProxyConfig.TargetConfig.class);
        when(pc.target()).thenReturn(tgt);
        when(tgt.host()).thenReturn("localhost");
        when(tgt.port()).thenReturn(8081);

        AuthContext auth = AuthContext.builder()
            .userId("u123")
            .email(null)
            .name(null)
            .roles(java.util.Set.of())
            .build();

        HttpClientRequest req = mock(HttpClientRequest.class);
        when(s.httpClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(io.smallrye.mutiny.Uni.createFrom().item(req));
        when(req.setTimeout(anyLong())).thenReturn(req);
        when(req.putHeader(anyString(), anyString())).thenReturn(req);

        HttpClientResponse mockResp = mock(HttpClientResponse.class);
        when(mockResp.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(mockResp.statusCode()).thenReturn(200);
        when(req.send()).thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockResp));

        s.proxy("GET", "/test", null, null, null, null, auth).await().indefinitely();

        verify(req).putHeader("X-Test", "u123---");
    }
}
