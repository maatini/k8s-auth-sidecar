package space.maatini.sidecar.util;

import java.util.List;

/**
 * Utility class for matching request paths against patterns.
 * Supports Ant-style path patterns:
 * <ul>
 * <li>{@code /api/users} — exact match</li>
 * <li>{@code /api/users/*} — matches one path segment (e.g.
 * /api/users/123)</li>
 * <li>{@code /api/users/**} — matches zero or more path segments</li>
 * </ul>
 */
public final class PathMatcher {

    private PathMatcher() {
        // Utility class
    }

    /**
     * Checks if a path matches the given pattern.
     *
     * @param path    The request path (e.g. "/api/users/123")
     * @param pattern The pattern to match against (e.g. "/api/users/**")
     * @return true if the path matches the pattern
     */
    public static boolean matches(String path, String pattern) {
        if (path == null || pattern == null) {
            return false;
        }

        // Normalize trailing slashes
        String normalizedPath = path.endsWith("/") && path.length() > 1
                ? path.substring(0, path.length() - 1)
                : path;

        if (pattern.endsWith("/**")) {
            String prefix = pattern.substring(0, pattern.length() - 3);
            // Match the prefix itself or anything beneath it
            return normalizedPath.equals(prefix)
                    || normalizedPath.startsWith(prefix + "/")
                    || prefix.isEmpty(); // /** matches everything
        }

        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            if (!normalizedPath.startsWith(prefix + "/")) {
                return false;
            }
            // Ensure only one additional segment (no nested slashes)
            String remainder = normalizedPath.substring(prefix.length() + 1);
            return !remainder.contains("/");
        }

        return normalizedPath.equals(pattern);
    }

    /**
     * Checks if a path matches any of the given patterns.
     *
     * @param path     The request path
     * @param patterns The list of patterns to match against
     * @return true if the path matches any pattern
     */
    public static boolean matchesAny(String path, List<String> patterns) {
        if (patterns == null) {
            return false;
        }
        for (String pattern : patterns) {
            if (matches(path, pattern)) {
                return true;
            }
        }
        return false;
    }
}
