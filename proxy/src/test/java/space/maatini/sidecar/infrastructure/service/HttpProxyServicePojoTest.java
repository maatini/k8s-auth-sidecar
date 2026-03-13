package space.maatini.sidecar.infrastructure.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.http.HttpClient;
import io.vertx.mutiny.core.http.HttpClientRequest;
import io.vertx.mutiny.core.http.HttpClientResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.domain.model.ProxyResponse;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HttpProxyServicePojoTest {

    private HttpProxyService proxyService;
    private SidecarConfig config;
    private HttpClient httpClient;
    private HttpClientRequest request;
    private HttpClientResponse response;
    private WebClient webClient;

    private Counter requestCounter;
    private Counter errorCounter;
    private Timer requestTimer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() throws Exception {
        proxyService = new HttpProxyService();
        config = mock(SidecarConfig.class);
        webClient = mock(WebClient.class);

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
        httpClient = mock(HttpClient.class);
        request = mock(HttpClientRequest.class);
        response = mock(HttpClientResponse.class);
        setField(proxyService, "httpClient", httpClient);
        setField(proxyService, "webClient", webClient);
        setField(proxyService, "meterRegistry", new SimpleMeterRegistry());
        setField(proxyService, "requestCounter", requestCounter);
        setField(proxyService, "errorCounter", errorCounter);
        setField(proxyService, "requestTimer", requestTimer);

        when(httpClient.request(any(HttpMethod.class), anyInt(), anyString(), anyString())).thenReturn(Uni.createFrom().item(request));
        when(request.setTimeout(anyLong())).thenReturn(request);
        when(request.putHeader(anyString(), anyString())).thenReturn(request);
        when(request.send()).thenReturn(Uni.createFrom().item(response));
        when(request.send(any(io.vertx.mutiny.core.http.HttpServerRequest.class))).thenReturn(Uni.createFrom().item(response));
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = HttpProxyService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_Success() {
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());

        AuthContext authContext = AuthContext.builder().userId("user1").build();

        ProxyResponse res = proxyService
                .proxy("GET", "/test", Collections.emptyMap(), Collections.emptyMap(), null, null, authContext)
                .await().indefinitely();

        assertNotNull(res);
        assertEquals(200, res.statusCode());

        verify(requestCounter).increment();
    }

    @Test
    void testProxy_Failure() {
        when(request.send()).thenReturn(Uni.createFrom().failure(new RuntimeException("fail")));

        AuthContext authContext = AuthContext.builder().userId("user1").build();

        ProxyResponse res = proxyService
                .proxy("GET", "/test", Collections.emptyMap(), Collections.emptyMap(), null, null, authContext)
                .await().indefinitely();

        assertNotNull(res);
        assertEquals(503, res.statusCode());
        assertTrue(res.bodyAsString().contains("Service Unavailable"));

        verify(errorCounter).increment();
    }

    @Test
    void testProxy_HeaderPropagation() {
        when(request.send()).thenReturn(Uni.createFrom().item(response));
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());

        SidecarConfig.ProxyConfig proxyConfig = config.proxy();
        when(proxyConfig.propagateHeaders()).thenReturn(List.of("X-Test-Propagate"));

        Map<String, String> incomingHeaders = Map.of("X-Test-Propagate", "test-value", "X-Hidden", "secret");

        proxyService.proxy("GET", "/test", incomingHeaders, null, null, null, AuthContext.anonymous())
                .await().indefinitely();

        verify(request).putHeader("X-Test-Propagate", "test-value");
        verify(request, never()).putHeader(eq("X-Hidden"), anyString());
    }

    @Test
    void testProxy_AuthContextPlaceholders() {
        when(request.send()).thenReturn(Uni.createFrom().item(response));
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());

        SidecarConfig.ProxyConfig proxyConfig = config.proxy();
        when(proxyConfig.addHeaders()).thenReturn(Map.of("X-User-Email", "${user.email}"));

        AuthContext authContext = AuthContext.builder()
                .userId("u123")
                .email("u123@example.com")
                .build();

        proxyService.proxy("GET", "/test", null, null, null, null, authContext)
                .await().indefinitely();

        verify(request).putHeader("X-User-Email", "u123@example.com");
    }

    @Test
    void testProxy_PostStreaming() {
        when(response.statusCode()).thenReturn(201);
        when(response.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
 
        io.vertx.core.http.HttpServerRequest mockClientReq = mock(io.vertx.core.http.HttpServerRequest.class);
 
        ProxyResponse res = proxyService.proxy("POST", "/upload", null, null, mockClientReq, null, AuthContext.anonymous())
                .await().indefinitely();
 
        assertEquals(201, res.statusCode());
        verify(mockClientReq).resume();
        verify(request).send(any(io.vertx.mutiny.core.http.HttpServerRequest.class));
    }

    @Test
    void testBuildTargetUrl() {
        assertEquals("http://localhost:8081/api/v1", proxyService.buildTargetUrl("/api/v1"));
    }

    @Test
    void testResolveAuthContextHeaders_Default() throws Exception {
        when(config.proxy().addHeaders()).thenReturn(Collections.emptyMap());
        AuthContext auth = AuthContext.builder()
                .userId("u1")
                .email("u1@maatini.space")
                .roles(Set.of("admin"))
                .build();

        java.lang.reflect.Method m = HttpProxyService.class.getDeclaredMethod("resolveAuthContextHeaders",
                AuthContext.class);
        m.setAccessible(true);
        Map<String, String> res = (Map<String, String>) m.invoke(proxyService, auth);

        assertEquals("u1", res.get("X-Auth-User-Id"));
        assertEquals("u1@maatini.space", res.get("X-Auth-User-Email"));
        assertEquals("admin", res.get("X-Auth-User-Roles"));
    }

    @Test
    void testResolvePropagatedHeaders_Special() throws Exception {
        when(config.proxy().propagateHeaders()).thenReturn(List.of("X-Correl"));
        Map<String, String> incoming = Map.of(
                "x-correl", "v1", // case insensitive
                "content-type", "application/json",
                "accept", "text/plain");

        java.lang.reflect.Method m = HttpProxyService.class.getDeclaredMethod("resolvePropagatedHeaders", Map.class);
        m.setAccessible(true);
        Map<String, String> res = (Map<String, String>) m.invoke(proxyService, incoming);

        assertEquals("v1", res.get("X-Correl"));
        assertEquals("application/json", res.get("Content-Type"));
        assertEquals("text/plain", res.get("Accept"));
    }

    @Test
    void testShutdown() throws Exception {
        java.lang.reflect.Method m = HttpProxyService.class.getDeclaredMethod("shutdown");
        m.setAccessible(true);
        m.invoke(proxyService);
        verify(webClient).close();
        verify(httpClient).close();
    }
}
