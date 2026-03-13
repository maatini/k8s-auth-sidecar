package space.maatini.sidecar.application.service;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Implementation of KeycloakRoleExtractor.
 */
@ApplicationScoped
@RegisterForReflection
public class KeycloakRoleExtractorImpl implements KeycloakRoleExtractor {

    private static final Logger LOG = Logger.getLogger(KeycloakRoleExtractorImpl.class);

    private static final String CLAIM_REALM_ACCESS = "realm_access";
    private static final String CLAIM_RESOURCE_ACCESS = "resource_access";

    @Inject
    JwtClaimExtractor claimExtractor;

    @Override
    public Set<String> extractRoles(JsonWebToken jwt) {
        Set<String> roles = new HashSet<>();
        roles.addAll(extractRealmRoles(jwt));
        roles.addAll(extractResourceRoles(jwt));
        roles.addAll(extractGroups(jwt));
        return roles;
    }

    @Override
    public Set<String> extractRealmRoles(JsonWebToken jwt) {
        Object rolesObj;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> realmAccess = claimExtractor.extractClaim(jwt, CLAIM_REALM_ACCESS, Map.class);
            if (realmAccess == null || !realmAccess.containsKey("roles")) {
                return Collections.emptySet();
            }
            rolesObj = realmAccess.get("roles");
        } catch (Exception e) {
            LOG.debugf("Failed to extract Keycloak realm roles: %s", e.getMessage());
            return Collections.emptySet();
        }

        return parseRoles(rolesObj);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> extractResourceRoles(JsonWebToken jwt) {
        Set<String> roles = new HashSet<>();
        Map<String, Object> resourceAccess;
        try {
            resourceAccess = claimExtractor.extractClaim(jwt, CLAIM_RESOURCE_ACCESS, Map.class);
        } catch (Exception e) {
            LOG.debugf("Failed to extract Keycloak resource roles: %s", e.getMessage());
            return roles;
        }

        if (resourceAccess != null) {
            for (Map.Entry<String, Object> entry : resourceAccess.entrySet()) {
                String clientId = entry.getKey();
                if (entry.getValue() instanceof Map) {
                    Map<String, Object> clientAccess = (Map<String, Object>) entry.getValue();
                    if (clientAccess.containsKey("roles")) {
                        Object rolesObj = clientAccess.get("roles");
                        if (rolesObj instanceof Collection) {
                            ((Collection<?>) rolesObj).stream()
                                    .filter(String.class::isInstance)
                                    .map(String.class::cast)
                                    .map(role -> clientId + ":" + role)
                                    .forEach(roles::add);
                        }
                    }
                }
            }
        }
        return roles;
    }

    @Override
    public Set<String> extractGroups(JsonWebToken jwt) {
        try {
            Set<String> groups = jwt.getGroups();
            if (groups != null) {
                return groups;
            }
        } catch (Exception e) {
            LOG.debugf("Failed to extract groups: %s", e.getMessage());
        }
        return Collections.emptySet();
    }

    private Set<String> parseRoles(Object rolesObj) {
        if (rolesObj instanceof Collection) {
            return ((Collection<?>) rolesObj).stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .collect(Collectors.toSet());
        }
        return Collections.emptySet();
    }
}
