package de.edeka.eit.sidecar.application.service;

import io.smallrye.mutiny.Uni;
import io.quarkus.vertx.http.runtime.security.ImmutablePathMatcher;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import de.edeka.eit.sidecar.domain.util.ExtAuthzUtils;
import de.edeka.eit.sidecar.infrastructure.config.SidecarConfig;
import de.edeka.eit.sidecar.domain.model.AuthContext;
import de.edeka.eit.sidecar.domain.model.ProcessingResult;
import de.edeka.eit.sidecar.domain.model.SidecarRequest;
import de.edeka.eit.sidecar.usecase.authorization.AuthorizationCommand;
import de.edeka.eit.sidecar.usecase.authorization.AuthorizationUseCase;

@ApplicationScoped
public class SidecarRequestProcessor {
    private static final Logger LOG = Logger.getLogger(SidecarRequestProcessor.class);

    private final AuthenticationService authenticationService;
    private final AuthorizationUseCase authorizationUseCase;
    private final SidecarConfig config;
    private final String nonAppRoot;

    private ImmutablePathMatcher<Boolean> publicPathMatcher;

    @Inject
    public SidecarRequestProcessor(AuthenticationService authenticationService,
                                   AuthorizationUseCase authorizationUseCase,
                                   SidecarConfig config,
                                   @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.http.non-application-root-path", defaultValue = "/q") String nonAppRoot) {
        this.authenticationService = authenticationService;
        this.authorizationUseCase = authorizationUseCase;
        this.config = config;
        this.nonAppRoot = nonAppRoot;
    }

    @PostConstruct
    void init() {
        ImmutablePathMatcher.ImmutablePathMatcherBuilder<Boolean> builder = ImmutablePathMatcher.builder();
        if (config.auth().publicPaths() != null) {
            for (String pattern : config.auth().publicPaths()) {
                builder.addPath(pattern, Boolean.TRUE);
            }
        }
        publicPathMatcher = builder.build();
    }

    /**
     * Orchestrates the sidecar processing pipeline in a REST-agnostic way.
     */
    public Uni<ProcessingResult> process(SidecarRequest request) {
        String path = request.path();
        String method = request.method();

        if (isPublicPath(path) || ExtAuthzUtils.isInternalPath(path, nonAppRoot)) {
            LOG.debugf("Skipping processing for path: %s", path);
            return Uni.createFrom().item(ProcessingResult.skip());
        }

        if (!config.auth().enabled()) {
            LOG.debug("Authentication is disabled");
            return Uni.createFrom().item(ProcessingResult.proceed(AuthContext.anonymous()));
        }

        return authenticationService.extractAuthContext(request.jwt())
                .flatMap(authContext -> {
                    if (!authContext.isAuthenticated()) {
                        LOG.warnf("Authentication failed for request: %s %s", method, path);
                        return Uni.createFrom().item(ProcessingResult.unauthorized("Authentication required"));
                    }
                    LOG.debugf("Authenticated user: %s", authContext.userId());

                    if (!config.authz().enabled()) {
                        return Uni.createFrom().item(ProcessingResult.proceed(authContext));
                    }

                    AuthorizationCommand command = new AuthorizationCommand(
                            authContext,
                            method,
                            path,
                            request.headers(),
                            request.queryParams()
                    );

                    return authorizationUseCase.execute(command)
                            .map(result -> {
                                if (!result.allowed()) {
                                    LOG.warnf("Authorization denied for user on %s %s: %s", method, path,
                                            result.reason());
                                    return (ProcessingResult) ProcessingResult.forbidden(result);
                                }
                                LOG.debugf("Authorization allowed for user %s on %s %s",
                                        authContext.userId(), method, path);
                                return (ProcessingResult) ProcessingResult.proceed(authContext);
                            });
                })
                .onFailure().recoverWithItem(error -> {
                    LOG.errorf(error, "Unexpected error during authentication/authorization");
                    return (ProcessingResult) ProcessingResult.error("Internal server error");
                });
    }

    private boolean isPublicPath(String path) {
        ImmutablePathMatcher.PathMatch<Boolean> match = publicPathMatcher.match(path);
        return match.getValue() == Boolean.TRUE;
    }
}


