package space.maatini.sidecar.infrastructure.route;

import io.quarkus.vertx.web.Route;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import space.maatini.sidecar.application.service.SidecarRequestProcessor;
import space.maatini.sidecar.domain.model.ProcessingResult;
import space.maatini.sidecar.domain.model.SidecarRequest;
import space.maatini.sidecar.infrastructure.util.RequestUtils;

import java.util.Map;

/**
 * Reactive route handler for Envoy ext_authz (PDP).
 * Replaces the streaming proxy logic with a pure authorization endpoint.
 */
@ApplicationScoped
public class SidecarRouteHandler {

    private final SidecarRequestProcessor processor;
    private final JsonWebToken jwt;

    @Inject
    public SidecarRouteHandler(SidecarRequestProcessor processor, JsonWebToken jwt) {
        this.processor = processor;
        this.jwt = jwt;
    }

    /**
     * Envoy ext_authz endpoint.
     * Checks if the request is authorized and returns enriched headers.
     */
    @Route(path = "/authorize", methods = Route.HttpMethod.GET)
    public Uni<Void> authorize(RoutingContext ctx) {
        HttpServerRequest vertxRequest = ctx.request();
        
        // Extract original request info from Envoy headers or fallback to current request
        String path = vertxRequest.getHeader("X-Envoy-Original-Path");
        if (path == null) {
            path = vertxRequest.path();
        }
        
        String method = vertxRequest.getHeader("X-Forwarded-Method");
        if (method == null) {
            method = vertxRequest.method().name();
        }

        Map<String, String> headers = RequestUtils.extractHeaders(vertxRequest);
        Map<String, String> queryParams = RequestUtils.extractQueryParams(ctx);

        SidecarRequest request = new SidecarRequest(method, path, headers, queryParams, jwt);

        return processor.process(request)
                .onItem().transformToUni(result -> {
                    HttpServerResponse response = ctx.response();
                    
                    if (result instanceof ProcessingResult.Proceed proceed) {
                        response.setStatusCode(200);
                        // Supplement Envoy request with enriched data
                        response.putHeader("X-Auth-User-Id", proceed.authContext().userId());
                        if (proceed.authContext().roles() != null && !proceed.authContext().roles().isEmpty()) {
                            response.putHeader("X-Enriched-Roles", String.join(",", proceed.authContext().roles()));
                        }
                        return Uni.createFrom().completionStage(response.end().toCompletionStage()).replaceWithVoid();
                    } else if (result instanceof ProcessingResult.Skip) {
                        response.setStatusCode(200).end();
                        return Uni.createFrom().nullItem();
                    } else if (result instanceof ProcessingResult.Forbidden forbidden) {
                        response.setStatusCode(403).end("{\"message\":\"Forbidden\",\"reason\":\"" + forbidden.result().reason() + "\"}");
                        return Uni.createFrom().nullItem();
                    } else if (result instanceof ProcessingResult.Unauthorized unauthorized) {
                        response.setStatusCode(401).end("{\"message\":\"Unauthorized\",\"reason\":\"" + unauthorized.message() + "\"}");
                        return Uni.createFrom().nullItem();
                    } else {
                        response.setStatusCode(500).end("{\"message\":\"Internal Server Error\"}");
                        return Uni.createFrom().nullItem();
                    }
                });
    }
}
