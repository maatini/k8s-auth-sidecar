package space.maatini.sidecar.health;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
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
import space.maatini.sidecar.config.SidecarConfig;

import java.time.Duration;

/**
 * Readiness health check for the sidecar.
 * Verifies that all required dependencies (backend, roles service, OPA) are
 * reachable.
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

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
    }

    @PreDestroy
    void shutdown() {
        if (webClient != null) {
            webClient.close();
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

        // Check OPA connectivity (if external)
        if (config.opa().enabled() && "external".equals(config.opa().mode())) {
            try {
                boolean opaHealthy = checkOpa();
                builder.withData("opa.connected", opaHealthy);
                if (!opaHealthy) {
                    allHealthy = false;
                }
            } catch (Exception e) {
                builder.withData("opa.connected", false);
                builder.withData("opa.error", e.getMessage());
                allHealthy = false;
            }
        } else {
            builder.withData("opa.mode", config.opa().mode());
        }

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
            // Try a simple TCP connection check
            try {
                var socket = vertx.createNetClient()
                        .connect(port, host)
                        .await().atMost(Duration.ofSeconds(2));
                socket.close();
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    /**
     * Checks if the external OPA server is reachable.
     */
    private boolean checkOpa() {
        String opaUrl = config.opa().external().url();

        try {
            var response = webClient.getAbs(opaUrl + "/health")
                    .timeout(2000)
                    .send()
                    .await().atMost(Duration.ofSeconds(3));

            return response.statusCode() == 200;
        } catch (Exception e) {
            LOG.debugf("OPA health check failed: %s", e.getMessage());
            return false;
        }
    }
}
