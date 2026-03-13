package space.maatini.sidecar.application.service;

import io.smallrye.mutiny.Uni;
import io.quarkus.vertx.http.runtime.security.ImmutablePathMatcher;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import space.maatini.sidecar.domain.util.ProxyUtils;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.domain.model.ProcessingResult;
import space.maatini.sidecar.domain.model.SidecarRequest;

@ApplicationScoped
public class SidecarRequestProcessor {
    private static final Logger LOG = Logger.getLogger(SidecarRequestProcessor.class);

    private final AuthenticationService authenticationService;
    private final RolesService rolesService;
    private final PolicyEngine policyEngine;
    private final SidecarConfig config;
    private final String nonAppRoot;

    private ImmutablePathMatcher<Boolean> publicPathMatcher;

    @Inject
    public SidecarRequestProcessor(AuthenticationService authenticationService,
                                   RolesService rolesService,
                                   PolicyEngine policyEngine,
                                   SidecarConfig config,
                                   @org.eclipse.microprofile.config.inject.ConfigProperty(name = "quarkus.http.non-application-root-path", defaultValue = "/q") String nonAppRoot) {
        this.authenticationService = authenticationService;
        this.rolesService = rolesService;
        this.policyEngine = policyEngine;
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

        if (isPublicPath(path) || ProxyUtils.isInternalPath(path, nonAppRoot)) {
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

                    return rolesService.enrich(authContext)
                            .flatMap(enrichedContext -> {
                                if (!config.authz().enabled()) {
                                    return Uni.createFrom().item(ProcessingResult.proceed(enrichedContext));
                                }
                                return policyEngine.evaluate(enrichedContext, method, path, request.headers(), request.queryParams())
                                        .map(decision -> {
                                            if (!decision.allowed()) {
                                                LOG.warnf("Authorization denied for user on %s %s: %s", method, path,
                                                        decision.reason());
                                                return (ProcessingResult) ProcessingResult.forbidden(decision);
                                            }
                                            LOG.debugf("Authorization allowed for user %s on %s %s",
                                                    enrichedContext.userId(), method, path);
                                            return (ProcessingResult) ProcessingResult.proceed(enrichedContext);
                                        });
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


