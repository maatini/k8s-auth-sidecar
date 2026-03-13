package space.maatini.sidecar.infrastructure.health;

import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.WebClient;
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;

import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthCheckPojoTest {

    private LivenessCheck livenessCheck;
    private ReadinessCheck readinessCheck;
    private SidecarConfig config;

    @BeforeEach
    void setUp() throws Exception {
        livenessCheck = new LivenessCheck();

        config = mock(SidecarConfig.class);
        SidecarConfig.ProxyConfig proxyConfig = mock(SidecarConfig.ProxyConfig.class);
        SidecarConfig.ProxyConfig.TargetConfig targetConfig = mock(SidecarConfig.ProxyConfig.TargetConfig.class);

        SidecarConfig.AuthConfig authConfig = mock(SidecarConfig.AuthConfig.class);

        when(config.auth()).thenReturn(authConfig);
        when(authConfig.enabled()).thenReturn(true);
        when(config.proxy()).thenReturn(proxyConfig);
        when(proxyConfig.target()).thenReturn(targetConfig);
        when(targetConfig.host()).thenReturn("localhost");
        when(targetConfig.port()).thenReturn(8080);

        SidecarConfig.OpaConfig opaConfig = mock(SidecarConfig.OpaConfig.class);
        when(config.opa()).thenReturn(opaConfig);
        when(opaConfig.enabled()).thenReturn(true);

        Field livenessConfigField = LivenessCheck.class.getDeclaredField("config");
        livenessConfigField.setAccessible(true);
        livenessConfigField.set(livenessCheck, config);

        readinessCheck = new ReadinessCheck();

        Field configField = ReadinessCheck.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(readinessCheck, config);

        Field webClientField = ReadinessCheck.class.getDeclaredField("webClient");
        webClientField.setAccessible(true);
        webClientField.set(readinessCheck, mock(WebClient.class));

        Field netClientField = ReadinessCheck.class.getDeclaredField("netClient");
        netClientField.setAccessible(true);
        netClientField.set(readinessCheck, mock(io.vertx.mutiny.core.net.NetClient.class));
    }

    @Test
    void testLivenessCheck_AuthDisabled() {
        SidecarConfig.AuthConfig auth = mock(SidecarConfig.AuthConfig.class);
        when(config.auth()).thenReturn(auth);
        when(auth.enabled()).thenReturn(false);

        HealthCheckResponse response = livenessCheck.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals(false, response.getData().get().get("auth.enabled"));
    }

    @Test
    void testReadinessCheck_HttpSuccess() throws Exception {
        io.vertx.mutiny.ext.web.client.HttpRequest<Buffer> request = mock(
                io.vertx.mutiny.ext.web.client.HttpRequest.class);
        io.vertx.mutiny.ext.web.client.HttpResponse<Buffer> httpResponse = mock(
                io.vertx.mutiny.ext.web.client.HttpResponse.class);
        WebClient client = mock(WebClient.class);

        Field webClientField = ReadinessCheck.class.getDeclaredField("webClient");
        webClientField.setAccessible(true);
        webClientField.set(readinessCheck, client);

        when(client.get(anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        when(request.send()).thenReturn(io.smallrye.mutiny.Uni.createFrom().item(httpResponse));
        when(httpResponse.statusCode()).thenReturn(200);

        HealthCheckResponse response = readinessCheck.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals(true, response.getData().get().get("backend.connected"));
    }

    @Test
    void testReadinessCheck_HttpError_500() throws Exception {
        io.vertx.mutiny.ext.web.client.HttpRequest<Buffer> request = mock(
                io.vertx.mutiny.ext.web.client.HttpRequest.class);
        io.vertx.mutiny.ext.web.client.HttpResponse<Buffer> httpResponse = mock(
                io.vertx.mutiny.ext.web.client.HttpResponse.class);
        WebClient client = mock(WebClient.class);

        Field webClientField = ReadinessCheck.class.getDeclaredField("webClient");
        webClientField.setAccessible(true);
        webClientField.set(readinessCheck, client);

        when(client.get(anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        when(request.send()).thenReturn(io.smallrye.mutiny.Uni.createFrom().item(httpResponse));
        when(httpResponse.statusCode()).thenReturn(500);

        // Socket fallback mock
        Field netClientField = ReadinessCheck.class.getDeclaredField("netClient");
        netClientField.setAccessible(true);
        io.vertx.mutiny.core.net.NetClient netClient = (io.vertx.mutiny.core.net.NetClient) netClientField.get(readinessCheck);
        
        when(netClient.connect(anyInt(), anyString()))
                .thenReturn(io.smallrye.mutiny.Uni.createFrom().failure(new RuntimeException("Socket fail")));

        HealthCheckResponse response = readinessCheck.call();
        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
        assertEquals(false, response.getData().get().get("backend.connected"));
    }

    @Test
    void testReadinessCheck_SocketFallback_Success() throws Exception {
        io.vertx.mutiny.ext.web.client.HttpRequest<Buffer> request = mock(
                io.vertx.mutiny.ext.web.client.HttpRequest.class);
        WebClient client = mock(WebClient.class);

        Field webClientField = ReadinessCheck.class.getDeclaredField("webClient");
        webClientField.setAccessible(true);
        webClientField.set(readinessCheck, client);

        when(client.get(anyInt(), anyString(), anyString())).thenReturn(request);
        when(request.timeout(anyLong())).thenReturn(request);
        // HTTP fails
        when(request.send()).thenReturn(io.smallrye.mutiny.Uni.createFrom().failure(new RuntimeException("HTTP fail")));

        // Socket fallback
        Field netClientField = ReadinessCheck.class.getDeclaredField("netClient");
        netClientField.setAccessible(true);
        io.vertx.mutiny.core.net.NetClient netClient = (io.vertx.mutiny.core.net.NetClient) netClientField.get(readinessCheck);
        io.vertx.mutiny.core.net.NetSocket netSocket = mock(io.vertx.mutiny.core.net.NetSocket.class);

        when(netClient.connect(anyInt(), anyString())).thenReturn(io.smallrye.mutiny.Uni.createFrom().item(netSocket));

        HealthCheckResponse response = readinessCheck.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals(true, response.getData().get().get("backend.connected"));
        verify(netSocket).close();
    }
}
