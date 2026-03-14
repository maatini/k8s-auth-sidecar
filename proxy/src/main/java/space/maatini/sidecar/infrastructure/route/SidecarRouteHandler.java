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
import space.maatini.sidecar.infrastructure.security.HeaderSanitizer;
import space.maatini.sidecar.infrastructure.util.RequestUtils;

import java.util.Map;

/**
 * Reactive route handler for Envoy ext_authz (PDP) authorization decisions.
 */
@ApplicationScoped
public class SidecarRouteHandler {

    /** Header name for the authenticated user ID enrichment. */
    public static final String HEADER_AUTH_USER_ID = "X-Auth-User-Id";

    /** Header name for the enriched roles list. */
    public static final String HEADER_ENRICHED_ROLES = "X-Enriched-Roles";

    private final SidecarRequestProcessor processor;
    private final JsonWebToken jwt;
    private final HeaderSanitizer headerSanitizer;

    @Inject
    public SidecarRouteHandler(SidecarRequestProcessor processor,
                               JsonWebToken jwt,
                               HeaderSanitizer headerSanitizer) {
        this.processor = processor;
        this.jwt = jwt;
        this.headerSanitizer = headerSanitizer;
    }

    /**
     * Envoy ext_authz endpoint.
     * Checks if the request is authorized and returns enriched headers.
     */
    @Route(path = "/authorize", methods = Route.HttpMethod.GET)
    public Uni<Void> authorize(RoutingContext ctx) {
        HttpServerRequest vertxRequest = ctx.request();

        // Extract and sanitize original request info (defense-in-depth against header spoofing)
        String path = headerSanitizer.extractPath(vertxRequest);
        String method = headerSanitizer.extractMethod(vertxRequest);

        // Strip Envoy-internal headers before passing to policy engine
        Map<String, String> headers = RequestUtils.extractHeaders(vertxRequest);
        headers.entrySet().removeIf(e -> headerSanitizer.isEnvoyInternalHeader(e.getKey()));

        Map<String, String> queryParams = RequestUtils.extractQueryParams(ctx);

        SidecarRequest request = new SidecarRequest(method, path, headers, queryParams, jwt);

        return processor.process(request)
                .onItem().transformToUni(result -> {
                    HttpServerResponse response = ctx.response();
                    
                    if (result instanceof ProcessingResult.Proceed proceed) {
                        response.setStatusCode(200);
                        // Supplement Envoy request with enriched data
                        response.putHeader(HEADER_AUTH_USER_ID, proceed.authContext().userId());
                        if (proceed.authContext().roles() != null && !proceed.authContext().roles().isEmpty()) {
                            response.putHeader(HEADER_ENRICHED_ROLES, String.join(",", proceed.authContext().roles()));
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
