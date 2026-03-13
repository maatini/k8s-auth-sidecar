package space.maatini.sidecar.usecase.authorization;

import java.util.List;

/**
 * Result object representing the outcome of an authorization decision.
 */
public record AuthorizationResult(
        boolean allowed,
        String reason,
        List<String> violations
) {
}
