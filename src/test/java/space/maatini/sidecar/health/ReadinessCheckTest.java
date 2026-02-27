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

class ReadinessCheckTest {

    ReadinessCheck readinessCheck;
    SidecarConfig config;
    WebClient mockWebClient;
    HttpRequest<Buffer> mockRequest;
    HttpResponse<Buffer> mockResponse;

    @BeforeEach
    void setup() throws Exception {
        readinessCheck = new ReadinessCheck();
        config = mock(SidecarConfig.class);
        mockWebClient = mock(WebClient.class);
        mockRequest = mock(HttpRequest.class);
        mockResponse = mock(HttpResponse.class);

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

        when(mockWebClient.get(anyInt(), anyString(), anyString())).thenReturn(mockRequest);
        when(mockWebClient.getAbs(anyString())).thenReturn(mockRequest);
        when(mockRequest.timeout(anyLong())).thenReturn(mockRequest);
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
        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        assertFalse((Boolean) response.getData().get().get("opa.connected"));
    }
}
