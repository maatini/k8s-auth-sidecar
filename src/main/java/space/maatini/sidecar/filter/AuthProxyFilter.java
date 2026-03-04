package space.maatini.sidecar.filter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.PolicyDecision;
import space.maatini.sidecar.service.AuthenticationService;
import space.maatini.sidecar.service.PolicyService;
import space.maatini.sidecar.service.ProxyService;
import space.maatini.sidecar.util.PathMatcher;
import space.maatini.sidecar.util.RequestUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Main request filter that intercepts all incoming requests and performs:
 * 1. Authentication (via Quarkus OIDC)
 * 2. Authorization (via embedded OPA policies)
 * 3. Proxying to the backend container
 */
@ApplicationScoped
public class AuthProxyFilter {

    private static final Logger LOG = Logger.getLogger(AuthProxyFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String AUTH_CONTEXT_PROPERTY = "auth.context";

    @Inject
    SidecarConfig config;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    AuthenticationService authenticationService;

    @Inject
    PolicyService policyService;

    @Inject
    ProxyService proxyService;

    @Inject
    MeterRegistry meterRegistry;

    @Context
    UriInfo uriInfo;

    @Context
    HttpServerRequest httpRequest;

    private Counter authSuccessCounter;
    private Counter authFailureCounter;
    private Counter authzAllowCounter;
    private Counter authzDenyCounter;
    private Timer authTimer;

    @PostConstruct
    void init() {
        authSuccessCounter = Counter.builder("sidecar.auth.success")
                .description("Successful authentications")
                .register(meterRegistry);

        authFailureCounter = Counter.builder("sidecar.auth.failure")
                .description("Failed authentications")
                .register(meterRegistry);

        authzAllowCounter = Counter.builder("sidecar.authz.allow")
                .description("Allowed authorization decisions")
                .register(meterRegistry);

        authzDenyCounter = Counter.builder("sidecar.authz.deny")
                .description("Denied authorization decisions")
                .register(meterRegistry);

        authTimer = Timer.builder("sidecar.auth.duration")
                .description("Authentication duration")
                .register(meterRegistry);
    }

    @ServerRequestFilter(priority = Priorities.AUTHORIZATION)
    public Uni<Response> filter(ContainerRequestContext requestContext) {
        long startTime = System.nanoTime();
        String path = requestContext.getUriInfo().getPath();
        String method = requestContext.getMethod();

        ensureRequestId(requestContext);

        if (isPublicPath(path)) {
            LOG.debugf("Skipping auth for public path: %s", path);
            return Uni.createFrom().nullItem();
        }

        if (PathMatcher.isInternalPath(path)) {
            LOG.debugf("Skipping auth for internal path: %s", path);
            return Uni.createFrom().nullItem();
        }

        if (!config.auth().enabled()) {
            LOG.debug("Authentication is disabled");
            return Uni.createFrom().nullItem();
        }

        return authenticationService.extractAuthContext(securityIdentity)
                .flatMap(authContext -> {
                    if (!authContext.isAuthenticated()) {
                        LOG.warnf("Authentication failed for request: %s %s", method, path);
                        authFailureCounter.increment();
                        return Uni.createFrom().item(createUnauthorizedResponse("Authentication required"));
                    }

                    authSuccessCounter.increment();
                    LOG.debugf("Authenticated user: %s", authContext.userId());

                    requestContext.setProperty(AUTH_CONTEXT_PROPERTY, authContext);

                    // Evaluate policy directly (no roles enrichment step)
                    if (!config.authz().enabled()) {
                        return Uni.createFrom().item((Response) null);
                    }

                    Map<String, String> headers = RequestUtils.extractHeaders(requestContext);
                    Map<String, String> queryParams = RequestUtils.extractQueryParams(requestContext.getUriInfo());

                    return policyService.evaluate(authContext, method, path, headers, queryParams)
                            .map(decision -> {
                                if (!decision.allowed()) {
                                    throw new AuthorizationDeniedException(decision);
                                }
                                authzAllowCounter.increment();
                                LOG.debugf("Authorization allowed for user %s on %s %s",
                                        authContext.userId(), method, path);
                                return (Response) null; // Continue filter chain
                            });
                }).onFailure().recoverWithItem(error -> {
                    AuthorizationDeniedException authzEx = findCause(error, AuthorizationDeniedException.class);
                    if (authzEx != null) {
                        LOG.warnf("Authorization denied for user on %s %s: %s",
                                method, path, authzEx.decision.reason());
                        authzDenyCounter.increment();
                        return createForbiddenResponse(authzEx.decision);
                    }
                    InternalAuthException intAuthEx = findCause(error, InternalAuthException.class);
                    if (intAuthEx != null) {
                        return createErrorResponse("Internal authentication error");
                    }

                    LOG.errorf(error, "Unexpected error during authentication/authorization: %s",
                            error.getClass().getName());
                    return createErrorResponse("Internal server error");
                }).onItemOrFailure().invoke((response, throwable) -> {
                    long duration = System.nanoTime() - startTime;
                    authTimer.record(duration, TimeUnit.NANOSECONDS);
                });
    }

    private static class AuthorizationDeniedException extends RuntimeException {
        final PolicyDecision decision;

        AuthorizationDeniedException(PolicyDecision decision) {
            super(decision.reason());
            this.decision = decision;
        }
    }

    private static class InternalAuthException extends RuntimeException {
        InternalAuthException(Throwable cause) {
            super(cause);
        }
    }

    private void ensureRequestId(ContainerRequestContext requestContext) {
        String requestId = requestContext.getHeaderString(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isEmpty()) {
            requestId = UUID.randomUUID().toString();
            requestContext.setProperty(REQUEST_ID_HEADER, requestId);
        }
    }

    private boolean isPublicPath(String path) {
        return PathMatcher.matchesAny(path, config.auth().publicPaths());
    }

    private Response createUnauthorizedResponse(String message) {
        return Response
                .status(Response.Status.UNAUTHORIZED)
                .entity(new ErrorResponse("unauthorized", message))
                .header("WWW-Authenticate", "Bearer")
                .build();
    }

    private Response createForbiddenResponse(PolicyDecision decision) {
        return Response
                .status(Response.Status.FORBIDDEN)
                .entity(new ErrorResponse(
                        "forbidden",
                        decision.reason() != null ? decision.reason() : "Access denied",
                        decision.violations()))
                .build();
    }

    private Response createErrorResponse(String message) {
        return Response
                .status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(new ErrorResponse("error", message))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        Throwable current = t;
        while (current != null) {
            if (type.isInstance(current)) {
                return (T) current;
            }
            current = current.getCause();
        }
        return null;
    }

    public record ErrorResponse(
            String code,
            String message,
            List<String> details) {
        public ErrorResponse(String code, String message) {
            this(code, message, null);
        }
    }
}
