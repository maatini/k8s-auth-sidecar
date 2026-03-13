package space.maatini.sidecar.infrastructure.health;
 
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
 
import java.lang.reflect.Field;
 
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
 
@io.quarkus.test.junit.QuarkusTest
class ReadinessCheckTest {
 
    private ReadinessCheck readinessCheck;
    private SidecarConfig config;
 
    @BeforeEach
    void setup() throws Exception {
        readinessCheck = new ReadinessCheck();
        config = mock(SidecarConfig.class);
        setField(readinessCheck, "config", config);
 
        SidecarConfig.OpaConfig opaConfig = mock(SidecarConfig.OpaConfig.class);
        when(config.opa()).thenReturn(opaConfig);
        when(opaConfig.enabled()).thenReturn(true);
    }
 
    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
 
    @Test
    void testCall_Healthy() {
        HealthCheckResponse response = readinessCheck.call();
        assertEquals(HealthCheckResponse.Status.UP, response.getStatus());
    }
}
