package space.maatini.sidecar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProxyServiceTest {

    ProxyService proxyService;
    SidecarConfig config;
    WebClient mockWebClient;
    HttpRequest<Buffer> mockRequest;
    HttpResponse<Buffer> mockResponse;

    @BeforeEach
    void setup() throws Exception {
        proxyService = new ProxyService();
        config = mock(SidecarConfig.class);
        mockWebClient = mock(WebClient.class);
        mockRequest = mock(HttpRequest.class);
        mockResponse = mock(HttpResponse.class);

        // Mock config
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
        when(proxyConfig.propagateHeaders()).thenReturn(java.util.List.of());

        // Manually inject dependencies
        setField(proxyService, "config", config);
        setField(proxyService, "meterRegistry", new SimpleMeterRegistry());
        setField(proxyService, "webClient", mockWebClient);
        setField(proxyService, "objectMapper", new ObjectMapper());

        // Manual initialization of metrics (mocking what @PostConstruct would do)
        setField(proxyService, "requestCounter", new SimpleMeterRegistry().counter("requests"));
        setField(proxyService, "errorCounter", new SimpleMeterRegistry().counter("errors"));
        setField(proxyService, "requestTimer", new SimpleMeterRegistry().timer("duration"));

        when(mockWebClient.request(any(HttpMethod.class), anyInt(), anyString(), anyString())).thenReturn(mockRequest);
        when(mockRequest.timeout(anyLong())).thenReturn(mockRequest);
        when(mockRequest.putHeaders(any())).thenReturn(mockRequest);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testProxy_Success() {
        when(mockRequest.send()).thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.statusMessage()).thenReturn("OK");
        when(mockResponse.headers())
                .thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap().add("Content-Type", "text/plain"));
        when(mockResponse.body()).thenReturn(Buffer.buffer("response body"));

        AuthContext authContext = AuthContext.builder().userId("user1").build();

        ProxyService.ProxyResponse response = proxyService.proxy(
                "GET", "/api/test", Map.of(), Map.of(), null, authContext).await().indefinitely();

        assertNotNull(response);
        assertEquals(200, response.statusCode());
        assertEquals("response body", response.bodyAsString());
    }

    @Test
    void testProxy_Failure() {
        when(mockRequest.send()).thenReturn(Uni.createFrom().failure(new RuntimeException("Network error")));

        AuthContext authContext = AuthContext.builder().userId("user1").build();

        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            proxyService.proxy("GET", "/api/test", Map.of(), Map.of(), null, authContext)
                    .await().indefinitely();
        });

        assertEquals("Network error", exception.getMessage());
    }
}
