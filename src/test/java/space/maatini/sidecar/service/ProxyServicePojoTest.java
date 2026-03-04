package space.maatini.sidecar.service;

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

        setField(proxyService, "config", config);
        setField(proxyService, "webClient", webClient);
        setField(proxyService, "meterRegistry", new SimpleMeterRegistry());
        setField(proxyService, "requestCounter", new SimpleMeterRegistry().counter("req"));
        setField(proxyService, "errorCounter", new SimpleMeterRegistry().counter("err"));
        setField(proxyService, "requestTimer", new SimpleMeterRegistry().timer("time"));

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
    }
}
