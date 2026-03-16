package de.edeka.eit.sidecar.usecase.authorization;

import de.edeka.eit.sidecar.domain.model.AuthContext;

import java.util.Map;

/**
 * Command containing all necessary parameters to trigger an authorization evaluation.
 */
public record AuthorizationCommand(
        AuthContext context,
        String method,
        String path,
        Map<String, String> headers,
        Map<String, String> queryParams
) {
}
