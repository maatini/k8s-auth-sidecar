package space.maatini.sidecar.infrastructure.service;

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
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.domain.model.ProxyResponse;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HttpProxyServicePojoTest {

    private HttpProxyService proxyService;
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
        proxyService = new HttpProxyService();
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
        Field field = HttpProxyService.class.getDeclaredField(name);
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

        ProxyResponse res = proxyService
                .proxy("GET", "/test", Collections.emptyMap(), Collections.emptyMap(), null, authContext)
                .await().indefinitely();

        assertNotNull(res);
        assertEquals(200, res.statusCode());
        assertEquals("ok", res.bodyAsString());

        verify(requestCounter).increment();
    }

    @Test
    void testProxy_Failure() {
        when(request.send()).thenReturn(Uni.createFrom().failure(new RuntimeException("fail")));

        AuthContext authContext = AuthContext.builder().userId("user1").build();

        ProxyResponse res = proxyService
                .proxy("GET", "/test", Collections.emptyMap(), Collections.emptyMap(), null, authContext)
                .await().indefinitely();

        assertNotNull(res);
        assertEquals(503, res.statusCode());
        assertTrue(res.bodyAsString().contains("Service Unavailable"));

        verify(errorCounter).increment();
    }

    @Test
    void testShutdown() throws Exception {
        java.lang.reflect.Method m = HttpProxyService.class.getDeclaredMethod("shutdown");
        m.setAccessible(true);
        m.invoke(proxyService);
        verify(webClient).close();
    }
}
