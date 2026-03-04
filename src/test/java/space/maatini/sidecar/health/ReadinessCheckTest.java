package space.maatini.sidecar.health;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.config.SidecarConfig;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadinessCheckTest {

    private ReadinessCheck readinessCheck;
    private WebClient webClient;
    private SidecarConfig config;

    @BeforeEach
    void setup() throws Exception {
        readinessCheck = new ReadinessCheck();
        webClient = mock(WebClient.class);
        config = mock(SidecarConfig.class);
        setField(readinessCheck, "webClient", webClient);
        setField(readinessCheck, "config", config);

        SidecarConfig.ProxyConfig proxyConfig = mock(SidecarConfig.ProxyConfig.class);
        SidecarConfig.ProxyConfig.TargetConfig targetConfig = mock(SidecarConfig.ProxyConfig.TargetConfig.class);
        SidecarConfig.OpaConfig opaConfig = mock(SidecarConfig.OpaConfig.class);

        when(config.proxy()).thenReturn(proxyConfig);
        when(proxyConfig.target()).thenReturn(targetConfig);
        when(config.opa()).thenReturn(opaConfig);
        when(opaConfig.enabled()).thenReturn(true);
        when(targetConfig.host()).thenReturn("backend");
        when(targetConfig.port()).thenReturn(8080);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testCall_Healthy() {
        HttpRequest<io.vertx.mutiny.core.buffer.Buffer> mockRequest = mock(HttpRequest.class);
        HttpResponse<io.vertx.mutiny.core.buffer.Buffer> mockResponse = mock(HttpResponse.class);

        // Correctly mock get() instead of request()
        when(webClient.get(anyInt(), anyString(), anyString())).thenReturn(mockRequest);
        when(mockRequest.timeout(anyLong())).thenReturn(mockRequest);
        when(mockRequest.send()).thenReturn(Uni.createFrom().item(mockResponse));
        when(mockResponse.statusCode()).thenReturn(200);

        HealthCheckResponse response = readinessCheck.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testCall_Unhealthy() {
        HttpRequest<io.vertx.mutiny.core.buffer.Buffer> mockRequest = mock(HttpRequest.class);
        when(webClient.get(anyInt(), anyString(), anyString())).thenReturn(mockRequest);
        when(mockRequest.timeout(anyLong())).thenReturn(mockRequest);
        when(mockRequest.send()).thenReturn(Uni.createFrom().failure(new RuntimeException("Connection refused")));

        HealthCheckResponse response = readinessCheck.call();
        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }
}
