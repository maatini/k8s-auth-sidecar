package de.edeka.eit.sidecar.domain.util;

import java.util.List;

/**
 * Utility class for sidecar ext-authz operations.
 */
public final class ExtAuthzUtils {

    /** Well-known management paths that should never be forwarded or auth-checked. */
    public static final List<String> MANAGEMENT_PATHS = List.of(
            "/health", "/metrics", "/ready", "/live"
    );

    /** Default Quarkus non-application root path. */
    public static final String DEFAULT_NON_APP_ROOT = "/q";

    private ExtAuthzUtils() {
        // Private constructor to prevent instantiation
    }

    /**
     * Checks if the given path is an internal Quarkus or sidecar management path.
     *
     * @param path       The path to check.
     * @param nonAppRoot The non-application root path (e.g., /q).
     * @return true if the path is internal, false otherwise.
     */
    public static boolean isInternalPath(String path, String nonAppRoot) {
        if (path == null) {
            return false;
        }

        String root = (nonAppRoot != null) ? nonAppRoot : DEFAULT_NON_APP_ROOT;
        if (!root.endsWith("/")) {
            root = root + "/";
        }

        if (path.startsWith(root)) {
            return true;
        }

        return MANAGEMENT_PATHS.contains(path);
    }
}
