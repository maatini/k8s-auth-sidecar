package space.maatini.sidecar.infrastructure.route;

import space.maatini.sidecar.domain.util.ProxyUtils;
import io.quarkus.vertx.web.Route;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import space.maatini.sidecar.application.service.ProxyService;
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.domain.model.SidecarRequest;
import space.maatini.sidecar.infrastructure.service.HttpProxyService;


/**
 * Reactive route handler for proxying and authorization endpoints.
 * Replaces ProxyResource.
 */
@ApplicationScoped
public class SidecarRouteHandler {

    private final ProxyService proxyService;

     @Inject
     public SidecarRouteHandler(HttpProxyService proxyService) {
         this.proxyService = proxyService;
     }

    @Route(path = "/authorize", methods = Route.HttpMethod.GET)
    public Uni<Void> authorize(RoutingContext ctx) {
        AuthContext auth = ctx.get("auth.context");
        if (auth == null || !auth.isAuthenticated()) {
            ctx.response().setStatusCode(401).end("{\"message\":\"Unauthorized\"}");
            return Uni.createFrom().nullItem();
        }
        ctx.response().setStatusCode(200).end();
        return Uni.createFrom().nullItem();
    }

    @Route(path = "/*", order = 1000) // Fallback for all other requests (Proxy)
    public Uni<Void> proxy(RoutingContext ctx) {
        SidecarRequest request = ctx.get("sidecar.request");
        AuthContext auth = ctx.get("auth.context");

        if (request == null) {
            ctx.fail(500);
            return Uni.createFrom().nullItem();
        }

        if (ProxyUtils.isInternalPath(request.path())) {
            ctx.next();
            return Uni.createFrom().nullItem();
        }

        return proxyService.proxy(
                request.method(),
                request.path(),
                request.headers(),
                request.queryParams(),
                ctx.request(),
                ctx.response(),
                auth
        ).onItem().transformToUni(proxyResponse -> {
            if (proxyResponse.isStreamed()) {
                // For streamed responses, status and headers are already set by HttpProxyService
                // and the body is already piped to the response.
                return Uni.createFrom().voidItem();
            }

            HttpServerResponse clientResponse = ctx.response();
            clientResponse.setStatusCode(proxyResponse.statusCode());
            if (proxyResponse.headers() != null) {
                proxyResponse.headers().forEach(clientResponse::putHeader);
            }
            if (proxyResponse.body() != null) {
                return Uni.createFrom().completionStage(clientResponse.end(proxyResponse.body().getDelegate()).toCompletionStage())
                        .replaceWithVoid();
            } else {
                return Uni.createFrom().completionStage(clientResponse.end().toCompletionStage())
                        .replaceWithVoid();
            }
        });
    }
}
