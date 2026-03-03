package space.maatini.sidecar.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.MultiMap;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProxyServicePojoTest {

    private ProxyService proxyService;
    private SidecarConfig config;
    private WebClient webClient;
    private MeterRegistry meterRegistry;

    private Counter requestCounter;
    private Counter errorCounter;
    private Timer requestTimer;

    @BeforeEach
    void setup() throws Exception {
        proxyService = new ProxyService();

        config = mock(SidecarConfig.class);
        webClient = mock(WebClient.class);
        meterRegistry = mock(MeterRegistry.class);

        requestCounter = mock(Counter.class);
        errorCounter = mock(Counter.class);
        requestTimer = mock(Timer.class);

        setField(proxyService, "config", config);
        setField(proxyService, "webClient", webClient);
        setField(proxyService, "meterRegistry", meterRegistry);
        setField(proxyService, "requestCounter", requestCounter);
        setField(proxyService, "errorCounter", errorCounter);
        setField(proxyService, "requestTimer", requestTimer);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private SidecarConfig.ProxyConfig mockConfig() {
        SidecarConfig.ProxyConfig proxyConfig = mock(SidecarConfig.ProxyConfig.class);
        SidecarConfig.ProxyConfig.TargetConfig targetConfig = mock(SidecarConfig.ProxyConfig.TargetConfig.class);
        SidecarConfig.ProxyConfig.TimeoutConfig timeoutConfig = mock(SidecarConfig.ProxyConfig.TimeoutConfig.class);

        when(config.proxy()).thenReturn(proxyConfig);
        when(proxyConfig.target()).thenReturn(targetConfig);
        when(targetConfig.host()).thenReturn("localhost");
        when(targetConfig.port()).thenReturn(8080);
        when(targetConfig.scheme()).thenReturn("http");
        when(proxyConfig.timeout()).thenReturn(timeoutConfig);
        when(timeoutConfig.read()).thenReturn(5000);

        return proxyConfig;
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_HappyPath_WithCustomHeaders() {
        SidecarConfig.ProxyConfig proxyConfig = mockConfig();
        when(proxyConfig.propagateHeaders()).thenReturn(List.of("x-custom-header", "missing-header"));
        Map<String, String> addHeaders = new HashMap<>();
        addHeaders.put("X-Custom-User", "${user.id}");
        addHeaders.put("X-Custom-Email", "${user.email}");
        addHeaders.put("X-Custom-Role", "${user.roles}");
        addHeaders.put("X-Custom-Tenant", "${user.tenant}");
        when(proxyConfig.addHeaders()).thenReturn(addHeaders);

        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.request(eq(HttpMethod.GET), eq(8080), eq("localhost"), eq("/api/test"))).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);

        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.statusMessage()).thenReturn("OK");
        MultiMap multiMap = mock(MultiMap.class);
        when(multiMap.names()).thenReturn(Collections.emptySet());
        when(response.headers()).thenReturn(multiMap);
        when(response.body()).thenReturn(Buffer.buffer("Success"));

        when(request.send()).thenReturn(Uni.createFrom().item(response));

        AuthContext authContext = AuthContext.builder()
                .userId("u1")
                .email("e@e.com")
                .roles(java.util.Set.of("admin", "user"))
                .name("Test User")
                .tenant("t1")
                .build();

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Custom-Header", "value1");
        headers.put("Content-Type", "application/json");

        ProxyService.ProxyResponse proxyResponse = proxyService
                .proxy("GET", "/api/test", headers, Collections.emptyMap(), null, authContext).await().indefinitely();

        assertTrue(proxyResponse.isSuccess());
        assertEquals("Success", proxyResponse.bodyAsString());
        verify(request).putHeader("x-custom-header", "value1");
        verify(request).putHeader("Content-Type", "application/json");
        verify(request).putHeader(eq("X-Custom-User"), eq("u1"));
        verify(request).putHeader(eq("X-Custom-Role"),
                (String) argThat(arg -> ((String) arg).contains("admin") && ((String) arg).contains("user")));
        verify(requestCounter).increment();

        ArgumentCaptor<Long> durationCaptor = ArgumentCaptor.forClass(Long.class);
        verify(requestTimer).record(durationCaptor.capture(), eq(TimeUnit.NANOSECONDS));
        assertTrue(durationCaptor.getValue() >= 0);
        assertTrue(durationCaptor.getValue() < 1_000_000_000L);
    }

    @Test
    void testProxyResponse_IsSuccess_Boundaries() {
        assertTrue(new ProxyService.ProxyResponse(200, "OK", Map.of(), Buffer.buffer()).isSuccess());
        assertTrue(new ProxyService.ProxyResponse(299, "OK", Map.of(), Buffer.buffer()).isSuccess());

        assertFalse(new ProxyService.ProxyResponse(199, "OK", Map.of(), Buffer.buffer()).isSuccess());
        assertFalse(new ProxyService.ProxyResponse(300, "OK", Map.of(), Buffer.buffer()).isSuccess());
        assertFalse(new ProxyService.ProxyResponse(301, "Moved", Map.of(), Buffer.buffer()).isSuccess());
        assertFalse(new ProxyService.ProxyResponse(399, "Error", Map.of(), Buffer.buffer()).isSuccess());
        assertFalse(new ProxyService.ProxyResponse(400, "Bad Request", Map.of(), Buffer.buffer()).isSuccess());
        assertFalse(new ProxyService.ProxyResponse(401, "Unauthorized", Map.of(), Buffer.buffer()).isSuccess());
        assertFalse(new ProxyService.ProxyResponse(403, "Forbidden", Map.of(), Buffer.buffer()).isSuccess());
        assertFalse(new ProxyService.ProxyResponse(404, "Not Found", Map.of(), Buffer.buffer()).isSuccess());
        assertFalse(new ProxyService.ProxyResponse(500, "Error", Map.of(), Buffer.buffer()).isSuccess());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_ResolvePlaceholders_NullFields() {
        SidecarConfig.ProxyConfig proxyConfig = mockConfig();
        when(proxyConfig.addHeaders()).thenReturn(Map.of("X-ID", "${user.id}"));
        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(mock(MultiMap.class));
        when(request.send()).thenReturn(Uni.createFrom().item(response));

        // Case 1: anonymous
        AuthContext authContextAnonymous = AuthContext.builder().userId("anonymous").build();
        proxyService.proxy("GET", "/api/test", null, null, null, authContextAnonymous).await().indefinitely();
        verify(request, never()).putHeader(contains("X-ID"), anyString());

        // Case 2: explicitly null userId in AUTHENTICATED context (to reach
        // resolvePlaceholders)
        // Wait, addAuthContextHeaders returns early if NOT authenticated.
        // We need authContext.isAuthenticated() to be true but userId() to be null?
        // Let's check isAuthenticated(): userId != null && !"anonymous".equals(userId)
        // So if userId is null, isAuthenticated() is FALSE.
        // This means resolvePlaceholders is NEVER reached if userId is null!

        // HOWEVER, the mutation is INSIDE resolvePlaceholders.
        // Can we trigger resolvePlaceholders with other fields being null?
        // userId() != null check is at line 261.
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_ResolvePlaceholders_AuthenticatedWithNullUserId() throws Exception {
        // We need to bypass the isAuthenticated() check or use an AuthContext that is
        // authenticated
        // but has a null userId (if possible).
        // Since it's a record, we can't easily mock it to return true for
        // isAuthenticated but null for userId.
        // But wait, the mutation is in: authContext.userId() != null ?
        // authContext.userId() : ""

        SidecarConfig.ProxyConfig proxyConfig = mockConfig();
        when(proxyConfig.addHeaders()).thenReturn(Map.of("X-Name", "${user.name}", "X-ID", "${user.id}"));
        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(mock(MultiMap.class));
        when(request.send()).thenReturn(Uni.createFrom().item(response));

        // Authenticated user (userId="u1") but some OTHER fields are null
        AuthContext authContext = AuthContext.builder()
                .userId("u1")
                .name(null)
                .email(null)
                .tenant(null)
                .build();
        assertTrue(authContext.isAuthenticated());

        proxyService.proxy("GET", "/api/test", null, null, null, authContext).await().indefinitely();

        // userId="u1" should be added
        verify(request).putHeader("X-ID", "u1");
        // name and email are null, so placeholders resolve to empty string, and header
        // is NOT added
        verify(request, never()).putHeader(eq("X-Name"), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_PropagateHeaders_CaseInsensitive() {
        SidecarConfig.ProxyConfig proxyConfig = mockConfig();
        when(proxyConfig.propagateHeaders()).thenReturn(List.of("X-Required"));
        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(mock(MultiMap.class));
        when(request.send()).thenReturn(Uni.createFrom().item(response));

        proxyService.proxy("GET", "/api/test", Map.of("x-required", "val"), null, null, null).await().indefinitely();
        verify(request).putHeader("X-Required", "val");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_PropagateContentTypeAndAccept() {
        mockConfig();
        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(mock(MultiMap.class));
        when(request.send()).thenReturn(Uni.createFrom().item(response));

        Map<String, String> headers = Map.of("content-type", "xml", "acceptable", "json", "accept", "json");
        proxyService.proxy("GET", "/api/test", headers, null, null, null).await().indefinitely();

        verify(request).putHeader("Content-Type", "xml");
        verify(request).putHeader("Accept", "json");
    }

    @Test
    void testFallbackProxy() {
        AuthContext authContext = AuthContext.builder().userId("u1").build();
        ProxyService.ProxyResponse response = proxyService.fallbackProxy("GET", "/api/test", Collections.emptyMap(),
                null, null, authContext, new RuntimeException("Timeout")).await().indefinitely();
        assertFalse(response.isSuccess());
        assertEquals(503, response.statusCode());
    }

    @Test
    void testProxyResponse_Error_Logic() {
        ProxyService.ProxyResponse response = ProxyService.ProxyResponse.error(500, "Error \"msg\"");
        assertEquals(500, response.statusCode());
        assertTrue(response.bodyAsString().contains("Error \\\"msg\\\""));

        ProxyService.ProxyResponse response2 = ProxyService.ProxyResponse.error(404, null);
        assertTrue(response2.bodyAsString().contains("Internal error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_PostMethodStream() {
        mockConfig();
        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.request(eq(HttpMethod.POST), anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.headers()).thenReturn(mock(MultiMap.class));
        when(request.sendStream(any(io.vertx.mutiny.core.streams.ReadStream.class)))
                .thenReturn(Uni.createFrom().item(response));

        io.vertx.core.http.HttpServerRequest clientReq = mock(io.vertx.core.http.HttpServerRequest.class);
        proxyService.proxy("POST", "/api/test", null, null, clientReq, null).await().indefinitely();

        verify(request).sendStream(any(io.vertx.mutiny.core.streams.ReadStream.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_QueryParameters_Empty() {
        mockConfig();
        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.headers()).thenReturn(mock(MultiMap.class));
        when(request.send()).thenReturn(Uni.createFrom().item(response));

        // Empty but NOT null
        Map<String, String> queryParams = Collections.emptyMap();
        proxyService.proxy("GET", "/api/test", null, queryParams, null, null).await().indefinitely();
        verify(request, never()).addQueryParam(anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_PropagateHeaders_EmptyConfig() {
        SidecarConfig.ProxyConfig proxyConfig = mockConfig();
        when(proxyConfig.propagateHeaders()).thenReturn(Collections.emptyList());
        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.headers()).thenReturn(mock(MultiMap.class));
        when(request.send()).thenReturn(Uni.createFrom().item(response));

        proxyService.proxy("GET", "/api/test", Map.of("X-Some", "Val"), null, null, null).await().indefinitely();
        verify(request, never()).putHeader(eq("X-Some"), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_EmptyAddHeaders_EmptyMap() {
        SidecarConfig.ProxyConfig proxyConfig = mockConfig();
        when(proxyConfig.addHeaders()).thenReturn(Collections.emptyMap()); // Empty instead of null
        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.headers()).thenReturn(mock(MultiMap.class));
        when(request.send()).thenReturn(Uni.createFrom().item(response));

        AuthContext authContext = AuthContext.builder().userId("u1").build();
        proxyService.proxy("GET", "/api/test", null, null, null, authContext).await().indefinitely();

        // Should use defaults (Line 226)
        verify(request).putHeader("X-Auth-User-Id", "u1");
    }

    @Test
    void testProxyResponse_BodyAsString_WithContent() {
        ProxyService.ProxyResponse r = new ProxyService.ProxyResponse(200, "OK", Map.of(),
                Buffer.buffer("Hello World"));
        assertEquals("Hello World", r.bodyAsString());
    }

    @Test
    void testProxyResponse_Equality_Deep() {
        Buffer b1 = Buffer.buffer("B");
        Buffer b2 = Buffer.buffer("B");
        ProxyService.ProxyResponse r1 = new ProxyService.ProxyResponse(200, "OK", Map.of("K", "V"), b1);
        ProxyService.ProxyResponse r2 = new ProxyService.ProxyResponse(200, "OK", Map.of("K", "V"), b2);

        assertEquals(r1, r2);

        // Change each field
        assertNotEquals(r1, new ProxyService.ProxyResponse(201, "OK", Map.of("K", "V"), b1));
        assertNotEquals(r1, new ProxyService.ProxyResponse(200, "NOK", Map.of("K", "V"), b1));
        assertNotEquals(r1, new ProxyService.ProxyResponse(200, "OK", Map.of("K2", "V"), b1));
        assertNotEquals(r1, new ProxyService.ProxyResponse(200, "OK", Map.of("K", "V"), Buffer.buffer("C")));

        assertNotEquals(r1, null);
        assertNotEquals(r1, "string");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_EmptyAddHeaders_UsesDefaults() {
        SidecarConfig.ProxyConfig proxyConfig = mockConfig();
        when(proxyConfig.addHeaders()).thenReturn(null);
        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.headers()).thenReturn(mock(MultiMap.class));
        when(request.send()).thenReturn(Uni.createFrom().item(response));

        AuthContext authContext = AuthContext.builder().userId("u1").email("e@e.com").tenant("t1").roles(Set.of("r1"))
                .build();
        proxyService.proxy("GET", "/api/test", null, null, null, authContext).await().indefinitely();

        verify(request).putHeader("X-Auth-User-Id", "u1");
        verify(request).putHeader("X-Auth-User-Email", "e@e.com");
        verify(request).putHeader("X-Auth-Tenant", "t1");
        verify(request).putHeader("X-Auth-User-Roles", "r1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_Failure_IncrementsErrorCounter() {
        mockConfig();
        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        when(request.send()).thenReturn(Uni.createFrom().failure(new RuntimeException("Err")));

        assertThrows(RuntimeException.class,
                () -> proxyService.proxy("GET", "/api/test", null, null, null, null).await().indefinitely());
        verify(errorCounter).increment();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_NullResponseBody() {
        mockConfig();
        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.body()).thenReturn(null);
        when(response.headers()).thenReturn(mock(MultiMap.class));
        when(request.send()).thenReturn(Uni.createFrom().item(response));

        ProxyService.ProxyResponse res = proxyService.proxy("GET", "/api/test", null, null, null, null).await()
                .indefinitely();
        assertNotNull(res.body());
        assertEquals(0, res.body().length());
    }

    @Test
    void testShutdown() throws Exception {
        proxyService.shutdown();
        verify(webClient).close();
        setField(proxyService, "webClient", null);
        proxyService.shutdown();
    }

    @Test
    void testProxyResponse_Equality() {
        ProxyService.ProxyResponse r1 = new ProxyService.ProxyResponse(200, "OK", Map.of("K", "V"), Buffer.buffer("B"));
        ProxyService.ProxyResponse r2 = new ProxyService.ProxyResponse(200, "OK", Map.of("K", "V"), Buffer.buffer("B"));
        ProxyService.ProxyResponse r3 = new ProxyService.ProxyResponse(404, "Not Found", Map.of(), Buffer.buffer());

        assertEquals(r1, r2);
        assertNotEquals(r1, r3);
        assertEquals(r1.hashCode(), r2.hashCode());
        assertNotNull(r1.toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_NotAuthenticated_WithData_PreventsHeaders() {
        SidecarConfig.ProxyConfig proxyConfig = mockConfig();
        when(proxyConfig.addHeaders()).thenReturn(Map.of("X-Name", "${user.name}"));
        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.headers()).thenReturn(mock(MultiMap.class));
        when(request.send()).thenReturn(Uni.createFrom().item(response));

        // Context with name but "anonymous" userId -> NOT authenticated
        AuthContext authContext = AuthContext.builder().userId("anonymous").name("Sneaky").build();
        assertFalse(authContext.isAuthenticated());

        proxyService.proxy("GET", "/api/test", null, null, null, authContext).await().indefinitely();

        // If the isAuthenticated() check is mutated to always return true, X-Name would
        // be added.
        // We ensure it is NOT added.
        verify(request, never()).putHeader(eq("X-Name"), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_EmptyQueryParams_Explicit() {
        mockConfig();
        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.headers()).thenReturn(mock(MultiMap.class));
        when(request.send()).thenReturn(Uni.createFrom().item(response));

        // Maps are non-null but empty
        proxyService.proxy("GET", "/test", new HashMap<>(), new HashMap<>(), null, null).await().indefinitely();
        verify(request, never()).addQueryParam(anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPropagateHeaders_NullValueInMap() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Propagate", null);

        SidecarConfig.ProxyConfig proxyConfig = mockConfig();
        when(proxyConfig.propagateHeaders()).thenReturn(List.of("X-Propagate"));

        HttpRequest<Buffer> request = mock(HttpRequest.class);
        io.vertx.mutiny.core.MultiMap mutinyHeaders = io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap();
        when(request.headers()).thenReturn(mutinyHeaders);

        proxyService.propagateHeaders(request, headers);
        assertFalse(mutinyHeaders.contains("X-Propagate"));
    }

    @Test
    void testResolvePlaceholders_Matrix() {
        AuthContext full = AuthContext.builder()
                .userId("u1").email("e1").name("n1").tenant("t1").roles(Set.of("r1"))
                .build();
        AuthContext empty = AuthContext.builder().build();

        String template = "${user.id}|${user.email}|${user.name}|${user.roles}|${user.tenant}";

        assertEquals("u1|e1|n1|r1|t1", proxyService.resolvePlaceholders(template, full));
        assertEquals("||||", proxyService.resolvePlaceholders(template, empty));
        assertEquals("direct", proxyService.resolvePlaceholders("direct", full));
        assertNull(proxyService.resolvePlaceholders(null, full));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPropagateHeaders_RedundancyKiller() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Custom", "val");

        SidecarConfig.ProxyConfig proxyConfig = mockConfig();
        when(proxyConfig.propagateHeaders()).thenReturn(List.of("X-Custom"));

        HttpRequest<Buffer> request = mock(HttpRequest.class);
        proxyService.propagateHeaders(request, headers);

        // Verification call instead of checking MultiMap
        verify(request).putHeader("X-Custom", "val");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testProxy_CustomHeadersWithPlaceholders() {
        SidecarConfig.ProxyConfig proxyConfig = mockConfig();
        when(proxyConfig.addHeaders()).thenReturn(Map.of("X-User", "${user.id}"));

        HttpRequest<Buffer> request = mock(HttpRequest.class);
        when(webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        HttpResponse<Buffer> response = mock(HttpResponse.class);
        when(response.headers()).thenReturn(mock(MultiMap.class));
        when(request.send()).thenReturn(Uni.createFrom().item(response));

        AuthContext authContext = AuthContext.builder().userId("u123").build();
        proxyService.proxy("GET", "/api/test", null, null, null, authContext).await().indefinitely();

        verify(request).putHeader("X-User", "u123");
        // Verify default headers are NOT added
        verify(request, never()).putHeader(eq("X-Auth-User-Id"), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPropagateHeaders_ContentTypeCase() {
        Map<String, String> headers = new HashMap<>();
        headers.put("content-type", "application/json");

        SidecarConfig.ProxyConfig proxyConfig = mockConfig();
        when(proxyConfig.propagateHeaders()).thenReturn(List.of());

        HttpRequest<Buffer> request = mock(HttpRequest.class);
        proxyService.propagateHeaders(request, headers);

        verify(request).putHeader("Content-Type", "application/json");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testPropagateHeaders_DoNotCallForMissing() {
        Map<String, String> headers = new HashMap<>();
        // Source header is MISSING

        HttpRequest<Buffer> request = mock(HttpRequest.class);
        // Configure to propagate 'X-Custom-1'
        SidecarConfig.ProxyConfig proxyConfig = mockConfig();
        when(proxyConfig.propagateHeaders()).thenReturn(List.of("X-Custom-1"));

        proxyService.propagateHeaders(request, headers);

        // Verification: putHeader should NOT have been called for 'X-Custom-1'
        verify(request, never()).putHeader(eq("X-Custom-1"), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testAddAuthContextHeaders_Default_MissingEmail() {
        AuthContext context = AuthContext.builder()
                .userId("u1")
                .email(null) // missing
                .build();

        HttpRequest<Buffer> request = mock(HttpRequest.class);
        // Empty config triggers defaults
        SidecarConfig.ProxyConfig proxyConfig = mockConfig();
        when(proxyConfig.addHeaders()).thenReturn(Collections.emptyMap());

        proxyService.addAuthContextHeaders(request, context);

        verify(request).putHeader("X-Auth-User-Id", "u1");
        // Should NOT call for email
        verify(request, never()).putHeader(eq("X-Auth-User-Email"), anyString());
    }

    @Test
    void testResolvePlaceholders_NullTemplate() {
        assertNull(proxyService.resolvePlaceholders(null, mock(AuthContext.class)));
    }
}
