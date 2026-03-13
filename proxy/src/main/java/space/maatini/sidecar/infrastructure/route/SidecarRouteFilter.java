package space.maatini.sidecar.infrastructure.route;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.web.RouteFilter;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import space.maatini.sidecar.application.service.SidecarRequestProcessor;
import space.maatini.sidecar.domain.model.ProcessingResult;
import space.maatini.sidecar.domain.model.SidecarRequest;
import space.maatini.sidecar.infrastructure.util.RequestUtils;

/**
 * Reactive route filter for authentication and authorization.
 * Replaces AuthProxyFilter.
 */
@ApplicationScoped
public class SidecarRouteFilter {

    private final SidecarRequestProcessor processor;
    private final SecurityIdentity securityIdentity;

    @Inject
    public SidecarRouteFilter(SidecarRequestProcessor processor, SecurityIdentity securityIdentity) {
        this.processor = processor;
        this.securityIdentity = securityIdentity;
    }

    @RouteFilter(100) // Lower number = higher priority
    public void filter(RoutingContext ctx) {
        ctx.request().pause();
        JsonWebToken jwt = securityIdentity.getPrincipal() instanceof JsonWebToken
                ? (JsonWebToken) securityIdentity.getPrincipal()
                : null;

        SidecarRequest sidecarRequest = new SidecarRequest(
                ctx.request().method().name(),
                ctx.request().path(),
                RequestUtils.extractHeaders(ctx),
                RequestUtils.extractQueryParams(ctx),
                jwt
        );

        processor.process(sidecarRequest)
                .subscribe().with(
                        result -> handleResult(result, sidecarRequest, ctx),
                        error -> handleError(error, ctx)
                );
    }

    private void handleResult(ProcessingResult result, SidecarRequest sidecarRequest, RoutingContext ctx) {
        if (result instanceof ProcessingResult.Proceed p) {
            ctx.put("auth.context", p.authContext());
            ctx.put("sidecar.request", sidecarRequest);
            ctx.next();
        } else if (result instanceof ProcessingResult.Skip) {
            ctx.put("sidecar.request", sidecarRequest);
            ctx.next();
        } else if (result instanceof ProcessingResult.Forbidden f) {
            renderError(ctx, 403, "forbidden", f.decision().reason());
        } else if (result instanceof ProcessingResult.Unauthorized u) {
            ctx.response().putHeader("WWW-Authenticate", "Bearer");
            renderError(ctx, 401, "unauthorized", u.message());
        } else if (result instanceof ProcessingResult.Error e) {
            renderError(ctx, 500, "error", e.message());
        } else {
            ctx.fail(500);
        }
    }

    private void handleError(Throwable error, RoutingContext ctx) {
        renderError(ctx, 500, "error", error.getMessage());
    }

    private void renderError(RoutingContext ctx, int status, String type, String message) {
        ctx.response()
                .setStatusCode(status)
                .putHeader("Content-Type", "application/json")
                .end("{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}");
    }
}
