package de.edeka.eit.sidecar.usecase.authorization;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import de.edeka.eit.sidecar.application.service.PolicyEngine;
import de.edeka.eit.sidecar.application.service.RolesService;
import de.edeka.eit.sidecar.domain.model.PolicyDecision;

/**
 * Pure Java UseCase orchestrating the authorization flow using the injected PolicyEngine.
 */
@ApplicationScoped
public class AuthorizationUseCase {

    private final PolicyEngine policyEngine;
    private final RolesService rolesService;

    @Inject
    public AuthorizationUseCase(PolicyEngine policyEngine, RolesService rolesService) {
        this.policyEngine = policyEngine;
        this.rolesService = rolesService;
    }

    /**
     * Executes the authorization check asynchronously.
     *
     * @param commandUni the command containing the context and request info
     * @return an AuthorizationResult indicating whether access is allowed or not
     */
    public Uni<AuthorizationResult> execute(AuthorizationCommand command) {
        return rolesService.enrich(command.context())
                .flatMap(enrichedContext ->
                        policyEngine.evaluate(
                                enrichedContext,
                                command.method(),
                                command.path(),
                                command.headers(),
                                command.queryParams()
                        ).map(this::mapToResult)
                );
    }

    private AuthorizationResult mapToResult(PolicyDecision decision) {
        return new AuthorizationResult(
                decision.allowed(),
                decision.reason(),
                decision.violations(),
                decision.permissions()
        );
    }
}
