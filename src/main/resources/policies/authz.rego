# Default Authorization Policy for RR-Sidecar
# This Rego policy defines access control rules for the sidecar

package authz

import future.keywords.if
import future.keywords.in

# Default deny
default allow := false

# Allow if user has superadmin role
allow if {
    "superadmin" in input.user.roles
}

# Allow admin paths for admin users
allow if {
    startswith(input.request.path, "/api/admin")
    "admin" in input.user.roles
}

# Allow user management for admin or user-manager
allow if {
    startswith(input.request.path, "/api/users")
    input.request.method in ["POST", "PUT", "DELETE", "PATCH"]
    role_match({"admin", "user-manager"})
}

# Allow read access to users for admin/user-manager/viewer
allow if {
    startswith(input.request.path, "/api/users")
    input.request.method == "GET"
    role_match({"admin", "user-manager", "viewer"})
}

# Allow read access to resources for users with viewer or higher role
allow if {
    startswith(input.request.path, "/api/")
    not startswith(input.request.path, "/api/admin")
    not startswith(input.request.path, "/api/users")
    input.request.method == "GET"
    role_match({"admin", "user", "viewer"})
}

# Allow write access to resources for users with user or higher role
allow if {
    startswith(input.request.path, "/api/")
    not startswith(input.request.path, "/api/admin")
    not startswith(input.request.path, "/api/users")
    input.request.method in ["POST", "PUT", "DELETE", "PATCH"]
    role_match({"admin", "user"})
}

# Public endpoints
allow if {
    public_path(input.request.path)
}

# User can access their own resources
allow if {
    # Path pattern: /api/users/{userId}/...
    path_parts := split(trim_prefix(input.request.path, "/"), "/")
    count(path_parts) >= 3
    path_parts[0] == "api"
    path_parts[1] == "users"
    path_parts[2] == input.user.id
}

# Helper: Check if user has any of the required roles
role_match(required_roles) if {
    some role in input.user.roles
    role in required_roles
}

# Helper: Check if path is public
public_path(path) if {
    path in ["/health", "/metrics", "/ready", "/live"]
}

public_path(path) if {
    startswith(path, "/api/public/")
}

public_path(path) if {
    startswith(path, "/q/")
}

# Resource-based access control example
# Deny access to sensitive resources unless explicitly allowed
deny[msg] if {
    startswith(input.request.path, "/api/sensitive")
    not "sensitive-data-access" in input.user.permissions
    msg := "Access to sensitive data requires explicit permission"
}

# Rate limiting information (for documentation, actual enforcement elsewhere)
metadata := {
    "version": "1.0.0",
    "description": "Default authorization policy for RR-Sidecar",
    "last_updated": "2024-01-01"
}
