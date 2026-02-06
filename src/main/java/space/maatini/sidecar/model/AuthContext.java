package space.maatini.sidecar.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable authentication context containing user information extracted from
 * JWT token
 * and enriched with roles/permissions from external services.
 */
public record AuthContext(
        String userId,
        String email,
        String name,
        String preferredUsername,
        String issuer,
        List<String> audience,
        Set<String> roles,
        Set<String> permissions,
        Map<String, Object> claims,
        long issuedAt,
        long expiresAt,
        String tokenId,
        String tenant) {
    /**
     * Creates an empty/anonymous auth context.
     */
    public static AuthContext anonymous() {
        return new AuthContext(
                "anonymous",
                null,
                null,
                null,
                null,
                Collections.emptyList(),
                Collections.emptySet(),
                Collections.emptySet(),
                Collections.emptyMap(),
                0,
                0,
                null,
                null);
    }

    /**
     * Builder for creating AuthContext instances.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns true if this is an authenticated context (not anonymous).
     */
    public boolean isAuthenticated() {
        return userId != null && !"anonymous".equals(userId);
    }

    /**
     * Returns true if the user has the specified role.
     */
    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    /**
     * Returns true if the user has any of the specified roles.
     */
    public boolean hasAnyRole(String... rolesToCheck) {
        if (roles == null)
            return false;
        for (String role : rolesToCheck) {
            if (roles.contains(role))
                return true;
        }
        return false;
    }

    /**
     * Returns true if the user has all of the specified roles.
     */
    public boolean hasAllRoles(String... rolesToCheck) {
        if (roles == null)
            return false;
        for (String role : rolesToCheck) {
            if (!roles.contains(role))
                return false;
        }
        return true;
    }

    /**
     * Returns true if the user has the specified permission.
     */
    public boolean hasPermission(String permission) {
        return permissions != null && permissions.contains(permission);
    }

    /**
     * Returns a claim value by key.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getClaim(String key) {
        if (claims == null)
            return Optional.empty();
        return Optional.ofNullable((T) claims.get(key));
    }

    /**
     * Returns true if the token is expired.
     */
    public boolean isExpired() {
        return expiresAt > 0 && System.currentTimeMillis() / 1000 > expiresAt;
    }

    /**
     * Builder for AuthContext.
     */
    public static class Builder {
        private String userId;
        private String email;
        private String name;
        private String preferredUsername;
        private String issuer;
        private List<String> audience = Collections.emptyList();
        private Set<String> roles = Collections.emptySet();
        private Set<String> permissions = Collections.emptySet();
        private Map<String, Object> claims = Collections.emptyMap();
        private long issuedAt;
        private long expiresAt;
        private String tokenId;
        private String tenant;

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder email(String email) {
            this.email = email;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder preferredUsername(String preferredUsername) {
            this.preferredUsername = preferredUsername;
            return this;
        }

        public Builder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Builder audience(List<String> audience) {
            this.audience = audience != null ? List.copyOf(audience) : Collections.emptyList();
            return this;
        }

        public Builder roles(Set<String> roles) {
            this.roles = roles != null ? Set.copyOf(roles) : Collections.emptySet();
            return this;
        }

        public Builder permissions(Set<String> permissions) {
            this.permissions = permissions != null ? Set.copyOf(permissions) : Collections.emptySet();
            return this;
        }

        public Builder claims(Map<String, Object> claims) {
            this.claims = claims != null ? Map.copyOf(claims) : Collections.emptyMap();
            return this;
        }

        public Builder issuedAt(long issuedAt) {
            this.issuedAt = issuedAt;
            return this;
        }

        public Builder expiresAt(long expiresAt) {
            this.expiresAt = expiresAt;
            return this;
        }

        public Builder tokenId(String tokenId) {
            this.tokenId = tokenId;
            return this;
        }

        public Builder tenant(String tenant) {
            this.tenant = tenant;
            return this;
        }

        public AuthContext build() {
            return new AuthContext(
                    userId,
                    email,
                    name,
                    preferredUsername,
                    issuer,
                    audience,
                    roles,
                    permissions,
                    claims,
                    issuedAt,
                    expiresAt,
                    tokenId,
                    tenant);
        }
    }
}
