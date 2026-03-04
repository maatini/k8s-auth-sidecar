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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@io.quarkus.test.junit.QuarkusTest
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

        setField(proxyService, "config", config);
        setField(proxyService, "meterRegistry", new SimpleMeterRegistry());
        setField(proxyService, "webClient", mockWebClient);
        setField(proxyService, "objectMapper", new ObjectMapper());

        setField(proxyService, "requestCounter", new SimpleMeterRegistry().counter("requests"));
        setField(proxyService, "errorCounter", new SimpleMeterRegistry().counter("errors"));
        setField(proxyService, "requestTimer", new SimpleMeterRegistry().timer("duration"));

        when(mockWebClient.request(any(HttpMethod.class), anyInt(), anyString(), anyString())).thenReturn(mockRequest);
        when(mockRequest.timeout(anyLong())).thenReturn(mockRequest);
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
    void testProxy_AddAuthHeaders_FromConfig() {
        when(mockRequest.send()).thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(mockResponse.body()).thenReturn(Buffer.buffer());

        when(config.proxy().addHeaders()).thenReturn(Map.of(
                "X-User", "${user.id}",
                "X-Email", "${user.email}"));

        AuthContext authContext = AuthContext.builder()
                .userId("u1")
                .email("e@e.com")
                .build();

        proxyService.proxy("GET", "/val", Map.of(), Map.of(), null, authContext).await().indefinitely();

        verify(mockRequest).putHeader("X-User", "u1");
        verify(mockRequest).putHeader("X-Email", "e@e.com");
    }
}
