package de.edeka.eit.sidecar.infrastructure.route;

import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Reactive route filter for audit logging and performance measurement.
 * Replaces AuditLogFilter.
 */
@ApplicationScoped
public class AuditRouteFilter {

    private static final Logger LOG = Logger.getLogger(AuditRouteFilter.class);

    @RouteFilter(1) // Executed very early
    public void filter(RoutingContext ctx) {
        long start = System.currentTimeMillis();
        
        ctx.addBodyEndHandler(v -> {
            long duration = System.currentTimeMillis() - start;
            int status = ctx.response().getStatusCode();
            String method = ctx.request().method().name();
            String path = ctx.request().path();
            
            LOG.infof("Request finished: %s %s -> %d (%dms)", method, path, status, duration);
        });
        
        ctx.next();
    }
}
