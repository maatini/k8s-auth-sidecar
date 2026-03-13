package space.maatini.sidecar.infrastructure.health;
 
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
import org.jboss.logging.Logger;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
 
import java.time.Duration;
 
/**
 * Readiness health check for the sidecar.
 * Verifies that the backend service is reachable.
 */
@Readiness
@ApplicationScoped
public class ReadinessCheck implements HealthCheck {
 
    private static final Logger LOG = Logger.getLogger(ReadinessCheck.class);
 
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
 
        boolean allHealthy = true;
 
        // Check backend connectivity
        try {
            boolean backendHealthy = checkBackend();
            builder.withData("backend.connected", backendHealthy);
            if (!backendHealthy) {
                allHealthy = false;
            }
        } catch (Exception e) {
            builder.withData("backend.connected", false);
            builder.withData("backend.error", e.getMessage());
            allHealthy = false;
        }
 
        // OPA is always embedded – no connectivity check needed
        builder.withData("opa.embedded", config.opa().enabled());
 
        if (allHealthy) {
            return builder.up().build();
        } else {
            return builder.down().build();
        }
    }
 
    /**
     * Checks if the backend service is reachable.
     */
    private boolean checkBackend() {
        String host = config.proxy().target().host();
        int port = config.proxy().target().port();
 
        try {
            var response = webClient.get(port, host, "/health")
                    .timeout(2000)
                    .send()
                    .await().atMost(Duration.ofSeconds(3));
 
            return response.statusCode() >= 200 && response.statusCode() < 500;
        } catch (Exception e) {
            LOG.debugf("Backend health check failed: %s", e.getMessage());
            try {
                var socket = netClient
                        .connect(port, host)
                        .await().atMost(Duration.ofSeconds(2));
                socket.close();
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }
}
