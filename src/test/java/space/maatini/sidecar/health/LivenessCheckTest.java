package space.maatini.sidecar.health;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import space.maatini.sidecar.config.SidecarConfig;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@io.quarkus.test.junit.QuarkusTest
class LivenessCheckTest {

    LivenessCheck livenessCheck;
    SidecarConfig config;

    @BeforeEach
    void setup() throws Exception {
        livenessCheck = new LivenessCheck();
        config = Mockito.mock(SidecarConfig.class);

        SidecarConfig.AuthConfig authConfig = Mockito.mock(SidecarConfig.AuthConfig.class);
        SidecarConfig.AuthzConfig authzConfig = Mockito.mock(SidecarConfig.AuthzConfig.class);
        SidecarConfig.OpaConfig opaConfig = Mockito.mock(SidecarConfig.OpaConfig.class);

        when(config.auth()).thenReturn(authConfig);
        when(config.authz()).thenReturn(authzConfig);
        when(config.opa()).thenReturn(opaConfig);

        when(authConfig.enabled()).thenReturn(true);
        when(authzConfig.enabled()).thenReturn(true);
        when(opaConfig.enabled()).thenReturn(false);

        setField(livenessCheck, "config", config);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testCall_ReturnsUp() {
        HealthCheckResponse response = livenessCheck.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
        assertEquals("sidecar-liveness", response.getName());
    }

    @Test
    void testCall_ContainsConfigData() {
        HealthCheckResponse response = livenessCheck.call();
        assertTrue(response.getData().isPresent());
        Map<String, Object> data = response.getData().get();
        assertEquals(true, data.get("auth.enabled"));
        assertEquals(true, data.get("authz.enabled"));
        assertEquals(false, data.get("opa.enabled"));
    }
}
