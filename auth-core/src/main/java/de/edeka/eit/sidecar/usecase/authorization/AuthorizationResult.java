package de.edeka.eit.sidecar.usecase.authorization;

import java.util.List;
import java.util.Set;

/**
 * Result object representing the outcome of an authorization decision.
 */
public record AuthorizationResult(
        boolean allowed,
        String reason,
        List<String> violations,
        Set<String> permissions
) {
}
