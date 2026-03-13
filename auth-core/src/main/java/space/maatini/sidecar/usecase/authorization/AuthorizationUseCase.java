package space.maatini.sidecar.usecase.authorization;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import space.maatini.sidecar.application.service.PolicyEngine;
import space.maatini.sidecar.domain.model.PolicyDecision;

/**
 * Pure Java UseCase orchestrating the authorization flow using the injected PolicyEngine.
 */
@ApplicationScoped
public class AuthorizationUseCase {

    private final PolicyEngine policyEngine;

    @Inject
    public AuthorizationUseCase(PolicyEngine policyEngine) {
        this.policyEngine = policyEngine;
    }

    /**
     * Executes the authorization check asynchronously.
     *
     * @param commandUni the command containing the context and request info
     * @return an AuthorizationResult indicating whether access is allowed or not
     */
    public Uni<AuthorizationResult> execute(Uni<AuthorizationCommand> commandUni) {
        return commandUni.flatMap(command ->
                policyEngine.evaluate(
                        command.context(),
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
                decision.violations()
        );
    }
}
