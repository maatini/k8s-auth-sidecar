package de.edeka.eit.sidecar.infrastructure.health;
 
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import de.edeka.eit.sidecar.infrastructure.config.SidecarConfig;
 
 
/**
 * Readiness health check for the sidecar.
 * Verifies that the backend service is reachable.
 */
@Readiness
@ApplicationScoped
public class ReadinessCheck implements HealthCheck {
 
 
    @Inject
    SidecarConfig config;
 
    @Inject
    Vertx vertx;
 
    private WebClient webClient;
    private io.vertx.mutiny.core.net.NetClient netClient;
 
    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
        this.netClient = vertx.createNetClient();
    }
 
    @PreDestroy
    void shutdown() {
        if (webClient != null) {
            webClient.close();
        }
        if (netClient != null) {
            netClient.close();
        }
    }
 
    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("sidecar-readiness");
 
        // OPA is always embedded – verify if enabled
        builder.withData("opa.enabled", config.opa().enabled());
 
        return builder.up().build();
    }
}
