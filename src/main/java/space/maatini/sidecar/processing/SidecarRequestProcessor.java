package space.maatini.sidecar.processing;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.jboss.logging.Logger;
import space.maatini.sidecar.common.config.SidecarConfig;
import space.maatini.sidecar.common.model.AuthContext;
import space.maatini.sidecar.authn.AuthenticationService;
import space.maatini.sidecar.policy.PolicyService;
import space.maatini.sidecar.roles.RolesService;
import space.maatini.sidecar.common.util.PathMatcher;
import space.maatini.sidecar.common.util.RequestUtils;
import java.util.Map;

@ApplicationScoped
public class SidecarRequestProcessor {
    private static final Logger LOG = Logger.getLogger(SidecarRequestProcessor.class);

    @Inject
    AuthenticationService authenticationService;
    @Inject
    RolesService rolesService;
    @Inject
    PolicyService policyService;
    @Inject
    SidecarConfig config;
    @Inject
    SecurityIdentity securityIdentity;

    public Uni<ProcessingResult> process(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath();
        String method = ctx.getMethod();

        if (isPublicPath(path) || PathMatcher.isInternalPath(path)) {
            LOG.debugf("Skipping processing for path: %s", path);
            return Uni.createFrom().item(ProcessingResult.skip());
        }

        if (!config.auth().enabled()) {
            LOG.debug("Authentication is disabled");
            return Uni.createFrom().item(ProcessingResult.proceed(AuthContext.anonymous()));
        }

        return authenticationService.extractAuthContext(securityIdentity)
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
                                Map<String, String> headers = RequestUtils.extractHeaders(ctx);
                                Map<String, String> queryParams = RequestUtils.extractQueryParams(ctx.getUriInfo());
                                return policyService.evaluate(enrichedContext, method, path, headers, queryParams)
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
        return PathMatcher.matchesAny(path, config.auth().publicPaths());
    }
}
