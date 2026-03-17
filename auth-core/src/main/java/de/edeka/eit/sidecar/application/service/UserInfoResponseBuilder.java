package de.edeka.eit.sidecar.application.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;
import de.edeka.eit.sidecar.domain.model.AuthContext;
import de.edeka.eit.sidecar.domain.model.UserInfoResponse;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Builds the UserInfo response from an enriched AuthContext.
 * Transforms the flat permission set (e.g. "orders:read") into a grouped
 * permissions map (e.g. {"orders": ["read", "write"]}) and a rights list.
 */
@ApplicationScoped
public class UserInfoResponseBuilder {

    private static final Logger LOG = Logger.getLogger(UserInfoResponseBuilder.class);

    /**
     * Builds a UserInfoResponse from the given AuthContext.
     *
     * @param ctx the enriched authentication context
     * @return a fully populated UserInfoResponse
     */
    public UserInfoResponse build(AuthContext ctx) {
        LOG.debugf("Building UserInfo response for user: %s", ctx.userId());

        List<String> roles = ctx.roles() != null
                ? ctx.roles().stream().sorted().collect(Collectors.toList())
                : Collections.emptyList();

        Set<String> flatPermissions = ctx.permissions() != null
                ? ctx.permissions()
                : Collections.emptySet();

        List<String> rights = flatPermissions.stream()
                .sorted()
                .collect(Collectors.toList());

        Map<String, List<String>> groupedPermissions = groupPermissions(flatPermissions);

        return new UserInfoResponse(
                ctx.userId(),
                ctx.name(),
                ctx.preferredUsername(),
                ctx.email(),
                roles,
                rights,
                groupedPermissions,
                ctx.expiresAt(),
                ctx.issuedAt());
    }

    /**
     * Groups flat permissions like "orders:read" into a map like {"orders": ["read"]}.
     * Permissions without a colon separator are placed under a "_global" key.
     */
    Map<String, List<String>> groupPermissions(Set<String> flatPermissions) {
        if (flatPermissions == null || flatPermissions.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, List<String>> grouped = new TreeMap<>();
        for (String perm : flatPermissions) {
            int colonIndex = perm.indexOf(':');
            if (colonIndex > 0 && colonIndex < perm.length() - 1) {
                String resource = perm.substring(0, colonIndex);
                String action = perm.substring(colonIndex + 1);
                grouped.computeIfAbsent(resource, k -> new ArrayList<>()).add(action);
            } else {
                grouped.computeIfAbsent("_global", k -> new ArrayList<>()).add(perm);
            }
        }

        // Sort actions within each resource for deterministic output
        grouped.values().forEach(Collections::sort);

        return Collections.unmodifiableMap(grouped);
    }
}
