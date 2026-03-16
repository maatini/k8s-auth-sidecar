package de.edeka.eit.sidecar.application.service;

import org.eclipse.microprofile.jwt.JsonWebToken;
import java.util.Set;

/**
 * Interface for extracting Keycloak-specific roles from JWT tokens.
 */
public interface KeycloakRoleExtractor {

    Set<String> extractRoles(JsonWebToken jwt);

    Set<String> extractRealmRoles(JsonWebToken jwt);

    Set<String> extractResourceRoles(JsonWebToken jwt);

    Set<String> extractGroups(JsonWebToken jwt);
}
