package space.maatini.sidecar.health;

import io.vertx.mutiny.ext.web.client.WebClient;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.config.SidecarConfig;

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
    }

    @Test
    void testLivenessCheck() {
        HealthCheckResponse response = livenessCheck.call();
        assertEquals("sidecar-liveness", response.getName());
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    }

    @Test
    void testReadinessCheck_Exception() {
        // webClient mock will throw NPE because get() returns null by default in mock
        HealthCheckResponse response = readinessCheck.call();
        assertEquals("sidecar-readiness", response.getName());
        assertEquals(HealthCheckResponse.Status.DOWN, response.getStatus());
    }
}
