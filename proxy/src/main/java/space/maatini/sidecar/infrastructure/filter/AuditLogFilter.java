package space.maatini.sidecar.infrastructure.filter;
 
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import org.jboss.resteasy.reactive.server.ServerResponseFilter;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.domain.model.AuthContext;
 
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import java.time.Instant;
import java.util.Set;
 
/**
 * Filter for audit logging.
 */
@ApplicationScoped
public class AuditLogFilter {
 
    private static final Logger LOG = Logger.getLogger("audit-log");
    private static final String START_TIME_PROPERTY = "audit.start_time";
    private static final String AUTH_CONTEXT_PROPERTY = "auth.context";
 
    @Inject
    SidecarConfig config;
 
    @ServerRequestFilter(priority = Priorities.USER)
    public void filterRequest(ContainerRequestContext requestContext) {
        if (!config.audit().enabled()) {
            return;
        }
        requestContext.setProperty(START_TIME_PROPERTY, System.currentTimeMillis());
    }
 
    @ServerResponseFilter
    public void filterResponse(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        if (!config.audit().enabled()) {
            return;
        }
 
        Long startTime = (Long) requestContext.getProperty(START_TIME_PROPERTY);
        long duration = startTime != null ? System.currentTimeMillis() - startTime : -1;
 
        AuthContext authContext = (AuthContext) requestContext.getProperty(AUTH_CONTEXT_PROPERTY);
 
        AuditEntry entry = buildAuditEntry(requestContext, responseContext, authContext, duration);
        logAudit(entry);
    }
 
    private AuditEntry buildAuditEntry(
            ContainerRequestContext req,
            ContainerResponseContext res,
            AuthContext auth,
            long durationMs) {
 
        String path = req.getUriInfo().getPath();
        String method = req.getMethod();
        int status = res.getStatus();
 
        UserInfo userInfo = auth != null
                ? new UserInfo(auth.userId(), auth.email(), auth.roles())
                : new UserInfo("anonymous", null, null);
 
        return new AuditEntry(
                Instant.now().toString(),
                method,
                path,
                status,
                durationMs,
                userInfo,
                req.getHeaderString("X-Request-ID"));
    }
 
    private void logAudit(AuditEntry entry) {
        LOG.infof("AUDIT: method=%s path=%s status=%d duration=%dms user=%s requestId=%s",
                entry.method(), entry.path(), entry.status(), entry.durationMs(),
                entry.user().id(), entry.requestId());
    }
 
    public record AuditEntry(
            String timestamp,
            String method,
            String path,
            int status,
            long durationMs,
            UserInfo user,
            String requestId) {
    }
 
    public record UserInfo(
            String id,
            String email,
            Set<String> roles) {
    }
}
