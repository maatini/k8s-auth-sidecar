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
     * @param path The path to check.
     * @return true if the path is internal, false otherwise.
     */
    public static boolean isInternalPath(String path) {
        if (path == null) {
            return false;
        }
        return path.startsWith("/q/") ||
                path.equals("/health") ||
                path.equals("/metrics") ||
                path.equals("/ready") ||
                path.equals("/live");
    }
}
