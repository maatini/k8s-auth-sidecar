package space.maatini.sidecar.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProxyServicePojoTest {

    private ProxyService proxyService;
    private SidecarConfig config;
    private WebClient webClient;
    private HttpRequest<Buffer> request;
    private HttpResponse<Buffer> response;

    private Counter requestCounter;
    private Counter errorCounter;
    private Timer requestTimer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        proxyService = new ProxyService();
        config = mock(SidecarConfig.class);
        webClient = mock(WebClient.class);
        request = mock(HttpRequest.class);
        response = mock(HttpResponse.class);

        SidecarConfig.ProxyConfig proxyConfig = mock(SidecarConfig.ProxyConfig.class);
        SidecarConfig.ProxyConfig.TargetConfig targetConfig = mock(SidecarConfig.ProxyConfig.TargetConfig.class);
        SidecarConfig.ProxyConfig.TimeoutConfig timeoutConfig = mock(SidecarConfig.ProxyConfig.TimeoutConfig.class);

        when(config.proxy()).thenReturn(proxyConfig);
        when(proxyConfig.target()).thenReturn(targetConfig);
        when(proxyConfig.timeout()).thenReturn(timeoutConfig);
        when(targetConfig.host()).thenReturn("localhost");
        when(targetConfig.port()).thenReturn(8081);
        when(targetConfig.scheme()).thenReturn("http");
        when(timeoutConfig.read()).thenReturn(5000);
        when(proxyConfig.propagateHeaders()).thenReturn(Collections.emptyList());
        when(proxyConfig.addHeaders()).thenReturn(Collections.emptyMap());

        requestCounter = mock(Counter.class);
        errorCounter = mock(Counter.class);
        requestTimer = mock(Timer.class);

        setField(proxyService, "config", config);
        setField(proxyService, "webClient", webClient);
        setField(proxyService, "meterRegistry", new SimpleMeterRegistry());
        setField(proxyService, "requestCounter", requestCounter);
        setField(proxyService, "errorCounter", errorCounter);
        setField(proxyService, "requestTimer", requestTimer);

        when(webClient.request(any(HttpMethod.class), anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        when(request.putHeader(anyString(), anyString())).thenReturn(request);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_Success() {
        when(request.send()).thenReturn(Uni.createFrom().item(response));
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(response.body()).thenReturn(Buffer.buffer("ok"));

        AuthContext authContext = AuthContext.builder().userId("user1").build();

        ProxyService.ProxyResponse res = proxyService
                .proxy("GET", "/test", Collections.emptyMap(), Collections.emptyMap(), null, authContext)
                .await().indefinitely();

        assertNotNull(res);
        assertEquals(200, res.statusCode());
        assertEquals("ok", res.bodyAsString());

        verify(requestCounter).increment();
        verify(requestTimer).record(anyLong(), eq(java.util.concurrent.TimeUnit.NANOSECONDS));
    }

    @Test
    void testProxy_Failure() {
        when(request.send()).thenReturn(Uni.createFrom().failure(new RuntimeException("fail")));

        AuthContext authContext = AuthContext.builder().userId("user1").build();

        ProxyService.ProxyResponse res = proxyService
                .proxy("GET", "/test", Collections.emptyMap(), Collections.emptyMap(), null, authContext)
                .await().indefinitely();

        assertNotNull(res);
        assertEquals(503, res.statusCode());
        assertTrue(res.bodyAsString().contains("Service Unavailable"));

        verify(errorCounter).increment();
    }

    @Test
    void testShutdown() throws Exception {
        java.lang.reflect.Method m = proxyService.getClass().getDeclaredMethod("shutdown");
        m.setAccessible(true);
        m.invoke(proxyService);
        verify(webClient).close();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_WithBodyAndHeaders() {
        // Setup WebClient and request for streaming
        io.vertx.core.http.HttpServerRequest clientReq = mock(io.vertx.core.http.HttpServerRequest.class);
        when(request.sendStream(any(io.vertx.mutiny.core.http.HttpServerRequest.class)))
                .thenReturn(Uni.createFrom().item(response));
        when(response.statusCode()).thenReturn(201);
        when(response.headers())
                .thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap().add("x-resp-h", "rv"));
        when(response.body()).thenReturn(Buffer.buffer("created"));

        // Setup config for headers
        when(config.proxy().propagateHeaders()).thenReturn(java.util.List.of("x-custom"));
        when(config.proxy().addHeaders()).thenReturn(java.util.Map.of("x-added", "av"));

        AuthContext authContext = AuthContext.builder()
                .userId("u1")
                .roles(java.util.Set.of("admin"))
                .build();

        java.util.Map<String, String> headers = java.util.Map.of("x-custom", "cv", "x-ignored", "iv");
        java.util.Map<String, String> queryParams = java.util.Map.of("q1", "qv");

        ProxyService.ProxyResponse res = proxyService
                .proxy("POST", "/target", headers, queryParams, clientReq, authContext)
                .await().indefinitely();

        assertNotNull(res);
        assertEquals(201, res.statusCode());
        assertEquals("created", res.bodyAsString());
        assertEquals("rv", res.headers().get("x-resp-h"));

        verify(request).addQueryParam("q1", "qv");
        verify(request).putHeader("x-custom", "cv");
        verify(request).putHeader("x-added", "av");
    }

    @Test
    void testProxy_WithNullAndEmptyQueryParams() {
        when(request.send()).thenReturn(Uni.createFrom().item(response));
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(response.body()).thenReturn(Buffer.buffer("ok"));

        proxyService.proxy("GET", "/test", null, null, null, null).await().indefinitely();
        verify(request, never()).addQueryParam(anyString(), anyString());

        proxyService.proxy("GET", "/test", null, java.util.Map.of(), null, null).await().indefinitely();
        verify(request, never()).addQueryParam(anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_WithMethodsThatSendClientRequestStream() {
        io.vertx.core.http.HttpServerRequest clientReq = mock(io.vertx.core.http.HttpServerRequest.class);
        when(request.sendStream(any(io.vertx.mutiny.core.http.HttpServerRequest.class)))
                .thenReturn(Uni.createFrom().item(response));
        when(request.send()).thenReturn(Uni.createFrom().item(response));
        when(response.statusCode()).thenReturn(204);
        when(response.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(response.body()).thenReturn(Buffer.buffer("ok"));

        // PUT
        proxyService.proxy("PUT", "/test", null, null, clientReq, null).await().indefinitely();
        verify(request, times(1)).sendStream(any(io.vertx.mutiny.core.http.HttpServerRequest.class));

        // PATCH
        proxyService.proxy("PATCH", "/test", null, null, clientReq, null).await().indefinitely();
        verify(request, times(2)).sendStream(any(io.vertx.mutiny.core.http.HttpServerRequest.class));

        // GET with clientReq should NOT sendStream but instead use .send()
        proxyService.proxy("GET", "/test", null, null, clientReq, null).await().indefinitely();
        verify(request, times(1)).send();
    }

    @Test
    void testShutdown_WithNullWebClient() throws Exception {
        setField(proxyService, "webClient", null);
        java.lang.reflect.Method m = proxyService.getClass().getDeclaredMethod("shutdown");
        m.setAccessible(true);
        m.invoke(proxyService);
        verify(webClient, never()).close();
    }
}
