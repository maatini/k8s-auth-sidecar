package space.maatini.sidecar.health;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.config.SidecarConfig;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@io.quarkus.test.junit.QuarkusTest
class ReadinessCheckTest {

    ReadinessCheck readinessCheck;
    SidecarConfig config;
    WebClient mockWebClient;
    HttpRequest<Buffer> mockRequest;
    HttpResponse<Buffer> mockResponse;
    io.vertx.mutiny.core.Vertx mockVertx;

    @BeforeEach
    void setup() throws Exception {
        readinessCheck = new ReadinessCheck();
        config = mock(SidecarConfig.class);
        mockWebClient = mock(WebClient.class);
        mockRequest = mock(HttpRequest.class);
        mockResponse = mock(HttpResponse.class);
        mockVertx = mock(io.vertx.mutiny.core.Vertx.class);

        // Mock config
        SidecarConfig.ProxyConfig proxyConfig = mock(SidecarConfig.ProxyConfig.class);
        SidecarConfig.ProxyConfig.TargetConfig targetConfig = mock(SidecarConfig.ProxyConfig.TargetConfig.class);
        SidecarConfig.OpaConfig opaConfig = mock(SidecarConfig.OpaConfig.class);

        when(config.proxy()).thenReturn(proxyConfig);
        when(proxyConfig.target()).thenReturn(targetConfig);
        when(targetConfig.host()).thenReturn("localhost");
        when(targetConfig.port()).thenReturn(8081);
        when(targetConfig.scheme()).thenReturn("http");

        when(config.opa()).thenReturn(opaConfig);
        when(opaConfig.enabled()).thenReturn(false);

        // Manually inject dependencies
        setField(readinessCheck, "config", config);
        setField(readinessCheck, "webClient", mockWebClient);
        setField(readinessCheck, "vertx", mockVertx);

        when(mockWebClient.get(anyInt(), anyString(), anyString())).thenReturn(mockRequest);
        when(mockWebClient.getAbs(anyString())).thenReturn(mockRequest);
        when(mockRequest.timeout(anyLong())).thenReturn(mockRequest);

        // Mock NetClient for TCP fallback
        io.vertx.mutiny.core.net.NetClient mockNetClient = mock(io.vertx.mutiny.core.net.NetClient.class);
        io.vertx.mutiny.core.net.NetSocket mockSocket = mock(io.vertx.mutiny.core.net.NetSocket.class);
        when(mockVertx.createNetClient()).thenReturn(mockNetClient);
        when(mockNetClient.connect(anyInt(), anyString())).thenReturn(Uni.createFrom().item(mockSocket));
        when(mockSocket.close()).thenReturn(Uni.createFrom().voidItem());
    }

    private void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testCall_Healthy() {
        when(mockRequest.send()).thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);

        HealthCheckResponse response = readinessCheck.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    }

    @Test
    void testCall_BackendDown() {
        when(mockRequest.send()).thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(503);

        HealthCheckResponse response = readinessCheck.call();
        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }

    @Test
    void testCall_OpaExternalHealthy() {
        SidecarConfig.OpaConfig opaConfig = config.opa();
        SidecarConfig.OpaConfig.ExternalOpaConfig externalConfig = mock(
                SidecarConfig.OpaConfig.ExternalOpaConfig.class);
        when(opaConfig.enabled()).thenReturn(true);
        when(opaConfig.mode()).thenReturn("external");
        when(opaConfig.external()).thenReturn(externalConfig);
        when(externalConfig.url()).thenReturn("http://opa:8181");

        when(mockRequest.send()).thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);

        HealthCheckResponse response = readinessCheck.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertTrue((Boolean) response.getData().get().get("opa.connected"));
    }

    @Test
    void testCall_OpaExternalDown() {
        SidecarConfig.OpaConfig opaConfig = config.opa();
        SidecarConfig.OpaConfig.ExternalOpaConfig externalConfig = mock(
                SidecarConfig.OpaConfig.ExternalOpaConfig.class);
        when(opaConfig.enabled()).thenReturn(true);
        when(opaConfig.mode()).thenReturn("external");
        when(opaConfig.external()).thenReturn(externalConfig);
        when(externalConfig.url()).thenReturn("http://opa:8181");

        // Backend healthy, OPA fails
        when(mockWebClient.get(anyInt(), anyString(), anyString())).thenReturn(mockRequest);
        @SuppressWarnings("unchecked")
        HttpRequest<Buffer> mockOpaRequest = mock(HttpRequest.class);
        when(mockOpaRequest.timeout(anyLong())).thenReturn(mockOpaRequest);
        when(mockWebClient.getAbs(anyString())).thenReturn(mockOpaRequest);

        when(mockRequest.send()).thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);

        when(mockOpaRequest.send()).thenReturn(Uni.createFrom().failure(new RuntimeException("OPA Down")));

        HealthCheckResponse response = readinessCheck.call();
        assertNotNull(response);
        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        assertFalse((Boolean) response.getData().get().get("opa.connected"));
    }

    @Test
    void testCall_BackendTimeout_TcpFallbackSuccess() {
        // HTTP times out
        when(mockRequest.send()).thenReturn(Uni.createFrom().failure(new RuntimeException("Timeout")));

        // TCP fallback (mocked in setup defaults to success)
        HealthCheckResponse response = readinessCheck.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertTrue((Boolean) response.getData().get().get("backend.connected"));
    }

    @Test
    void testCall_BackendAndTcpFailure() {
        when(mockRequest.send()).thenReturn(Uni.createFrom().failure(new RuntimeException("Timeout")));

        // Mock NetClient failure
        io.vertx.mutiny.core.net.NetClient mockNetClient = mock(io.vertx.mutiny.core.net.NetClient.class);
        when(mockVertx.createNetClient()).thenReturn(mockNetClient);
        when(mockNetClient.connect(anyInt(), anyString()))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("TCP Refused")));

        HealthCheckResponse response = readinessCheck.call();
        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        assertFalse((Boolean) response.getData().get().get("backend.connected"));
    }

    @Test
    void testCall_OpaExternalTimeout() {
        SidecarConfig.OpaConfig opaConfig = config.opa();
        SidecarConfig.OpaConfig.ExternalOpaConfig externalConfig = mock(
                SidecarConfig.OpaConfig.ExternalOpaConfig.class);
        when(opaConfig.enabled()).thenReturn(true);
        when(opaConfig.mode()).thenReturn("external");
        when(opaConfig.external()).thenReturn(externalConfig);
        when(externalConfig.url()).thenReturn("http://opa:8181");

        // Backend healthy
        when(mockRequest.send()).thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);

        // OPA times out
        @SuppressWarnings("unchecked")
        HttpRequest<Buffer> mockOpaRequest = mock(HttpRequest.class);
        when(mockOpaRequest.timeout(anyLong())).thenReturn(mockOpaRequest);
        when(mockWebClient.getAbs(anyString())).thenReturn(mockOpaRequest);
        when(mockOpaRequest.send()).thenReturn(Uni.createFrom().failure(new RuntimeException("Timeout")));

        HealthCheckResponse response = readinessCheck.call();
        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        assertFalse((Boolean) response.getData().get().get("opa.connected"));
    }

    @Test
    void testCall_OpaInternalMode() {
        SidecarConfig.OpaConfig opaConfig = config.opa();
        when(opaConfig.enabled()).thenReturn(true);
        when(opaConfig.mode()).thenReturn("embedded");

        when(mockRequest.send()).thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);

        HealthCheckResponse response = readinessCheck.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals("embedded", response.getData().get().get("opa.mode"));
    }
}
