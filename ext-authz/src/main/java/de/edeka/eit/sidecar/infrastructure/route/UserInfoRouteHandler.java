package de.edeka.eit.sidecar.infrastructure.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.vertx.web.Route;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import de.edeka.eit.sidecar.application.service.SidecarRequestProcessor;
import de.edeka.eit.sidecar.application.service.UserInfoResponseBuilder;
import de.edeka.eit.sidecar.domain.model.ProcessingResult;
import de.edeka.eit.sidecar.domain.model.SidecarRequest;
import de.edeka.eit.sidecar.infrastructure.security.HeaderSanitizer;
import de.edeka.eit.sidecar.infrastructure.util.RequestUtils;

import java.util.Map;

/**
 * Reactive route handler for the GET /userinfo endpoint.
 * Returns structured user information extracted from a valid JWT token.
 */
@ApplicationScoped
public class UserInfoRouteHandler {

    private static final Logger LOG = Logger.getLogger(UserInfoRouteHandler.class);
    private static final String CONTENT_TYPE_JSON = "application/json";

    private final SidecarRequestProcessor processor;
    private final UserInfoResponseBuilder responseBuilder;
    private final JsonWebToken jwt;
    private final HeaderSanitizer headerSanitizer;
    private final ObjectMapper objectMapper;

    @Inject
    public UserInfoRouteHandler(SidecarRequestProcessor processor,
                                UserInfoResponseBuilder responseBuilder,
                                JsonWebToken jwt,
                                HeaderSanitizer headerSanitizer,
                                ObjectMapper objectMapper) {
        this.processor = processor;
        this.responseBuilder = responseBuilder;
        this.jwt = jwt;
        this.headerSanitizer = headerSanitizer;
        this.objectMapper = objectMapper;
    }

    /**
     * GET /userinfo – returns user identity, roles, and permissions as JSON.
     * Reuses the same authentication/authorization pipeline as /authorize.
     * Returns 200 when the token is valid, 401 when not, 403 when OPA denies
     * (Forbidden does not carry AuthContext), 500 on errors.
     */
    @Route(path = "/userinfo", methods = Route.HttpMethod.GET)
    public Uni<Void> userinfo(RoutingContext ctx) {
        HttpServerRequest vertxRequest = ctx.request();

        String path = headerSanitizer.extractPath(vertxRequest);
        String method = headerSanitizer.extractMethod(vertxRequest);

        Map<String, String> headers = RequestUtils.extractHeaders(vertxRequest);
        headers.entrySet().removeIf(e -> headerSanitizer.isEnvoyInternalHeader(e.getKey()));

        Map<String, String> queryParams = RequestUtils.extractQueryParams(ctx);

        SidecarRequest request = new SidecarRequest(method, path, headers, queryParams, jwt);

        return processor.process(request)
                .onItem().transformToUni(result -> handleResult(ctx.response(), result));
    }

    private Uni<Void> handleResult(HttpServerResponse response, ProcessingResult result) {
        response.putHeader("Content-Type", CONTENT_TYPE_JSON);

        if (result instanceof ProcessingResult.Proceed proceed) {
            return writeUserInfo(response, proceed);
        } else if (result instanceof ProcessingResult.Forbidden forbidden) {
            // Token is valid but OPA denied – Forbidden does not carry AuthContext
            LOG.debugf("OPA denied /userinfo but token is valid, returning 403");
            String reason = forbidden.result() != null ? forbidden.result().reason() : "Access denied";
            response.setStatusCode(403).end("{\"message\":\"Forbidden\",\"reason\":\"" + reason + "\"}");
            return Uni.createFrom().nullItem();
        } else if (result instanceof ProcessingResult.Unauthorized unauthorized) {
            response.setStatusCode(401).end("{\"message\":\"Unauthorized\",\"reason\":\"" + unauthorized.message() + "\"}");
            return Uni.createFrom().nullItem();
        } else if (result instanceof ProcessingResult.Error error) {
            response.setStatusCode(500).end("{\"message\":\"Internal Server Error\",\"reason\":\"" + error.message() + "\"}");
            return Uni.createFrom().nullItem();
        } else {
            // Skip – should not happen for /userinfo, but handle gracefully
            response.setStatusCode(200).end("{}");
            return Uni.createFrom().nullItem();
        }
    }

    private Uni<Void> writeUserInfo(HttpServerResponse response, ProcessingResult.Proceed proceed) {
        try {
            var userInfo = responseBuilder.build(proceed.authContext());
            String json = objectMapper.writeValueAsString(userInfo);
            response.setStatusCode(200);
            return Uni.createFrom().completionStage(response.end(json).toCompletionStage()).replaceWithVoid();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to serialize UserInfo response");
            response.setStatusCode(500).end("{\"message\":\"Internal Server Error\"}");
            return Uni.createFrom().nullItem();
        }
    }
}
