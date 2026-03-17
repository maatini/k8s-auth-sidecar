package de.edeka.eit.sidecar.domain.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

/**
 * Immutable response model for the GET /userinfo endpoint.
 * Contains user identity, roles, rights, and structured permissions.
 */
@RegisterForReflection
public record UserInfoResponse(
        String sub,
        String name,
        String preferredUsername,
        String email,
        List<String> roles,
        List<String> rights,
        Map<String, List<String>> permissions,
        long exp,
        long iat) {
}
