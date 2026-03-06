package space.maatini.sidecar.infrastructure.health;
 
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
 
/**
 * Liveness health check for the sidecar.
 * Indicates whether the application is running and responsive.
 */
@Liveness
@ApplicationScoped
public class LivenessCheck implements HealthCheck {
 
    @Inject
    SidecarConfig config;
 
    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("sidecar-liveness")
                .withData("auth.enabled", config.auth().enabled())
                .withData("opa.enabled", config.opa().enabled());
 
        return builder.up().build();
    }
}
