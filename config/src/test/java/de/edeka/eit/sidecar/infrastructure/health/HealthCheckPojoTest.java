package de.edeka.eit.sidecar.infrastructure.health;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import de.edeka.eit.sidecar.infrastructure.config.SidecarConfig;

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
        SidecarConfig.AuthConfig authConfig = mock(SidecarConfig.AuthConfig.class);

        when(config.auth()).thenReturn(authConfig);
        when(authConfig.enabled()).thenReturn(true);

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
    void testReadinessCheck_Success() {
        HealthCheckResponse response = readinessCheck.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals(true, response.getData().get().get("opa.enabled"));
    }
}
