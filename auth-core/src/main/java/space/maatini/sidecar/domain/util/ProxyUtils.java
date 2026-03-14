package space.maatini.sidecar.domain.util;

/**
 * Utility class for sidecar proxy operations.
 */
public final class ProxyUtils {

    private ProxyUtils() {
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

        String root = (nonAppRoot != null) ? nonAppRoot : "/q";
        if (!root.endsWith("/")) {
            root = root + "/";
        }

        return path.startsWith(root) ||
                path.equals("/health") ||
                path.equals("/metrics") ||
                path.equals("/ready") ||
                path.equals("/live");
    }
}
