package space.maatini.sidecar.model;

import java.util.Set;

/**
 * Response from the external roles/permissions service.
 */
public record RolesResponse(
        String userId,
        Set<String> roles,
        Set<String> permissions,
        String tenant) {
    /**
     * Creates an empty response for a user with no roles.
     */
    public static RolesResponse empty(String userId) {
        return new RolesResponse(userId, Set.of(), Set.of(), null);
    }

    /**
     * Returns true if the user has the specified role.
     */
    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    /**
     * Returns true if the user has the specified permission.
     */
    public boolean hasPermission(String permission) {
        return permissions != null && permissions.contains(permission);
    }
}
