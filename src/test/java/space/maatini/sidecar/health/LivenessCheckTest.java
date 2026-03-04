package space.maatini.sidecar.health;

import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.config.SidecarConfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@io.quarkus.test.junit.QuarkusTest
class LivenessCheckTest {

    @Test
    void testCall() {
        LivenessCheck check = new LivenessCheck();
        SidecarConfig config = mock(SidecarConfig.class);
        check.config = config;

        SidecarConfig.AuthConfig auth = mock(SidecarConfig.AuthConfig.class);
        SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);

        when(config.auth()).thenReturn(auth);
        when(config.opa()).thenReturn(opa);
        when(auth.enabled()).thenReturn(true);
        when(opa.enabled()).thenReturn(false);

        HealthCheckResponse res = check.call();
        assertEquals(HealthCheckResponse.Status.UP, res.getStatus());
        assertEquals(true, res.getData().get().get("auth.enabled"));
        assertEquals(false, res.getData().get().get("opa.enabled"));
    }
}
