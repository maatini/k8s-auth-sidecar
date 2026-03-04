package space.maatini.sidecar.model;

import java.util.Map;
import java.util.Set;

/**
 * Input model for OPA policy evaluation.
 * This structure is passed to the embedded WASM policy engine for authorization
 * decisions.
 */
public record PolicyInput(
        RequestInfo request,
        UserInfo user,
        ResourceInfo resource,
        Map<String, Object> context) {

    /**
     * Creates a PolicyInput from AuthContext and request information.
     */
    public static PolicyInput from(
            AuthContext authContext,
            String method,
            String path,
            Map<String, String> headers,
            Map<String, String> queryParams) {

        return new PolicyInput(
                new RequestInfo(method, path, headers, queryParams),
                new UserInfo(
                        authContext.userId(),
                        authContext.email(),
                        authContext.roles(),
                        authContext.permissions()),
                ResourceInfo.fromPath(path),
                Map.of("source", "k8s-auth-sidecar"));
    }

    /**
     * Request information for policy evaluation.
     */
    public record RequestInfo(
            String method,
            String path,
            Map<String, String> headers,
            Map<String, String> queryParams) {

        public String[] pathSegments() {
            if (path == null || path.isEmpty()) {
                return new String[0];
            }
            String cleanPath = path.startsWith("/") ? path.substring(1) : path;
            return cleanPath.split("/");
        }
    }

    /**
     * User information for policy evaluation.
     */
    public record UserInfo(
            String id,
            String email,
            Set<String> roles,
            Set<String> permissions) {
    }

    /**
     * Resource information extracted from the request path.
     */
    public record ResourceInfo(
            String type,
            String id,
            String action) {

        public static ResourceInfo fromPath(String path) {
            if (path == null || path.isEmpty()) {
                return new ResourceInfo(null, null, null);
            }

            String[] segments = path.split("/");
            String type = null;
            String id = null;

            int resourceIndex = -1;
            for (int i = 0; i < segments.length; i++) {
                String segment = segments[i];
                if (segment.isEmpty() || segment.equals("api") || segment.matches("v\\d+")) {
                    continue;
                }
                if (resourceIndex == -1) {
                    type = segment;
                    resourceIndex = i;
                } else if (i == resourceIndex + 1 && !segment.isEmpty()) {
                    id = segment;
                    break;
                }
            }

            return new ResourceInfo(type, id, null);
        }
    }
}
