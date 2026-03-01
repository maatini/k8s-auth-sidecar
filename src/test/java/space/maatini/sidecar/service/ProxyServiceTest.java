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
        when(proxyConfig.poolSize()).thenReturn(100);
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

    @Test
    void testProxy_WithBody_SendsStream() {
        when(mockRequest
                .sendStream((io.vertx.mutiny.core.streams.ReadStream<Buffer>) org.mockito.ArgumentMatchers.any()))
                .thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.statusMessage()).thenReturn("OK");
        when(mockResponse.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(mockResponse.body()).thenReturn(Buffer.buffer("ok"));

        AuthContext authContext = AuthContext.builder().userId("user1").build();
        io.vertx.core.http.HttpServerRequest mockClientRequest = mock(io.vertx.core.http.HttpServerRequest.class);

        proxyService.proxy("POST", "/api/data", Map.of(), Map.of(), mockClientRequest, authContext).await()
                .indefinitely();

        verify(mockRequest)
                .sendStream((io.vertx.mutiny.core.streams.ReadStream<Buffer>) org.mockito.ArgumentMatchers.any());
        verify(mockRequest, never()).send();
    }

    @Test
    void testProxy_WithBody_SendsStream_PUT() {
        when(mockRequest
                .sendStream((io.vertx.mutiny.core.streams.ReadStream<Buffer>) org.mockito.ArgumentMatchers.any()))
                .thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.statusMessage()).thenReturn("OK");
        when(mockResponse.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(mockResponse.body()).thenReturn(Buffer.buffer("ok"));

        AuthContext authContext = AuthContext.builder().userId("user1").build();
        io.vertx.core.http.HttpServerRequest mockClientRequest = mock(io.vertx.core.http.HttpServerRequest.class);

        proxyService.proxy("PUT", "/api/data", Map.of(), Map.of(), mockClientRequest, authContext).await()
                .indefinitely();

        verify(mockRequest)
                .sendStream((io.vertx.mutiny.core.streams.ReadStream<Buffer>) org.mockito.ArgumentMatchers.any());
    }

    @Test
    void testProxy_WithBody_SendsStream_PATCH() {
        when(mockRequest
                .sendStream((io.vertx.mutiny.core.streams.ReadStream<Buffer>) org.mockito.ArgumentMatchers.any()))
                .thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.statusMessage()).thenReturn("OK");
        when(mockResponse.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(mockResponse.body()).thenReturn(Buffer.buffer("ok"));

        AuthContext authContext = AuthContext.builder().userId("user1").build();
        io.vertx.core.http.HttpServerRequest mockClientRequest = mock(io.vertx.core.http.HttpServerRequest.class);

        proxyService.proxy("PATCH", "/api/data", Map.of(), Map.of(), mockClientRequest, authContext).await()
                .indefinitely();

        verify(mockRequest)
                .sendStream((io.vertx.mutiny.core.streams.ReadStream<Buffer>) org.mockito.ArgumentMatchers.any());
    }

    @Test
    void testProxy_WithClientRequestButGET_DoesNotStream() {
        when(mockRequest.send()).thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.statusMessage()).thenReturn("OK");
        when(mockResponse.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(mockResponse.body()).thenReturn(Buffer.buffer("ok"));

        AuthContext authContext = AuthContext.builder().userId("user1").build();
        io.vertx.core.http.HttpServerRequest mockClientRequest = mock(io.vertx.core.http.HttpServerRequest.class);

        proxyService.proxy("GET", "/api/data", Map.of(), Map.of(), mockClientRequest, authContext).await()
                .indefinitely();

        verify(mockRequest).send();
        verify(mockRequest, never())
                .sendStream((io.vertx.mutiny.core.streams.ReadStream<Buffer>) org.mockito.ArgumentMatchers.any());
    }

    @Test
    void testProxy_WithQueryParams_AddsToRequest() {
        when(mockRequest.send()).thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.statusMessage()).thenReturn("OK");
        when(mockResponse.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(mockResponse.body()).thenReturn(Buffer.buffer("ok"));

        AuthContext authContext = AuthContext.builder().userId("user1").build();
        Map<String, String> queryParams = Map.of("foo", "bar", "baz", "qux");

        proxyService.proxy("GET", "/api/data", Map.of(), queryParams, null, authContext).await().indefinitely();

        verify(mockRequest).addQueryParam("foo", "bar");
        verify(mockRequest).addQueryParam("baz", "qux");
    }

    @Test
    void testProxy_WithHeaders_PropagatesMatchingHeaders() {
        when(mockRequest.send()).thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.statusMessage()).thenReturn("OK");
        when(mockResponse.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(mockResponse.body()).thenReturn(Buffer.buffer("ok"));

        // Configure propagated headers
        when(config.proxy().propagateHeaders()).thenReturn(java.util.List.of("X-Request-ID", "X-Custom"));

        AuthContext authContext = AuthContext.builder().userId("user1").build();
        Map<String, String> headers = Map.of(
                "X-Request-ID", "id-123",
                "X-Custom", "custom-val",
                "Authorization", "Bearer token" // Should NOT be propagated by propagateHeaders (handled by auth)
        );

        proxyService.proxy("GET", "/api/test", headers, Map.of(), null, authContext).await().indefinitely();

        // Check that headers were put into the request
        verify(mockRequest).putHeader("X-Request-ID", "id-123");
        verify(mockRequest).putHeader("X-Custom", "custom-val");
        verify(mockRequest, never()).putHeader("Authorization", "Bearer token");
    }

    @Test
    void testProxy_AddHeaders_FromConfig() {
        when(mockRequest.send()).thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.statusMessage()).thenReturn("OK");
        when(mockResponse.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(mockResponse.body()).thenReturn(Buffer.buffer("ok"));

        // Configure addHeaders
        when(config.proxy().addHeaders()).thenReturn(Map.of("X-Sidecar", "RR"));

        AuthContext authContext = AuthContext.builder().userId("user1").build();

        proxyService.proxy("GET", "/api/test", Map.of(), Map.of(), null, authContext).await().indefinitely();

        verify(mockRequest).putHeader("X-Sidecar", "RR");
    }

    @Test
    void testProxy_PropagateHeaders_CaseInsensitive() {
        when(mockRequest.send()).thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(config.proxy().propagateHeaders()).thenReturn(java.util.List.of("X-REQUEST-ID"));

        AuthContext authContext = AuthContext.builder().userId("user1").build();
        // Lowercase in map, uppercase in config
        Map<String, String> headers = Map.of("x-request-id", "id-1234");

        proxyService.proxy("GET", "/api/test", headers, null, null, authContext).await().indefinitely();
        verify(mockRequest).putHeader("X-REQUEST-ID", "id-1234");
    }

    @Test
    void testProxy_PropagateHeaders_NullHeadersAndNullAuth() {
        when(mockRequest.send()).thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());

        // Run with completely null headers, empty query params, and null AuthContext
        assertDoesNotThrow(() -> {
            proxyService.proxy("POST", "/test", null, Map.of(), null, null).await().indefinitely();
        });
    }

    @Test
    void testProxy_AddAuthHeaders_WithPlaceholders() {
        when(mockRequest.send()).thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
        when(mockResponse.body()).thenReturn(Buffer.buffer());

        when(config.proxy().addHeaders()).thenReturn(Map.of(
                "X-User", "${user.id}",
                "X-Email", "${user.email}",
                "X-Roles", "${user.roles}",
                "X-Tenant", "${user.tenant}",
                "X-Name", "${user.name}"));

        AuthContext authContext = AuthContext.builder()
                .userId("u1")
                .email("e@e.com")
                .roles(java.util.Set.of("r1"))
                .tenant("t1")
                .name("n1")
                .build();

        proxyService.proxy("GET", "/val", Map.of(), Map.of(), null, authContext).await().indefinitely();

        verify(mockRequest).putHeader("X-User", "u1");
        verify(mockRequest).putHeader("X-Email", "e@e.com");
        verify(mockRequest).putHeader("X-Roles", "r1");
        verify(mockRequest).putHeader("X-Tenant", "t1");
        verify(mockRequest).putHeader("X-Name", "n1");
    }

    @Test
    void testFallbackProxy_Returns503() {
        ProxyService.ProxyResponse response = proxyService.fallbackProxy("GET", "/api/test", Map.of(), Map.of(), null,
                null, new RuntimeException("error")).await().indefinitely();
        assertEquals(503, response.statusCode());
        assertTrue(response.bodyAsString().contains("Service Unavailable"));
    }

    @Test
    void testProxyResponse_Error_CreatesValidJson() {
        ProxyService.ProxyResponse response = ProxyService.ProxyResponse.error(500, "Internal Error");
        assertEquals(500, response.statusCode());
        assertTrue(response.bodyAsString().contains("\"error\":\"Internal Error\""));
    }

    @Test
    void testProxyResponse_Error_SanitizesDoubleQuotes() {
        ProxyService.ProxyResponse response = ProxyService.ProxyResponse.error(500, "Error with \"quotes\"");
        assertTrue(response.bodyAsString().contains("\"error\":\"Error with \\\"quotes\\\"\""));
    }

    @Test
    void testProxyResponse_IsSuccess() {
        assertTrue(new ProxyService.ProxyResponse(200, "OK", Map.of(), Buffer.buffer()).isSuccess());
        assertTrue(new ProxyService.ProxyResponse(201, "Created", Map.of(), Buffer.buffer()).isSuccess());
        assertFalse(new ProxyService.ProxyResponse(400, "Bad Request", Map.of(), Buffer.buffer()).isSuccess());
        assertFalse(new ProxyService.ProxyResponse(500, "Error", Map.of(), Buffer.buffer()).isSuccess());
    }
}
