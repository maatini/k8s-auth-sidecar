package de.edeka.eit.sidecar.infrastructure.util;

import io.quarkus.vertx.http.runtime.security.ImmutablePathMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Quarkus ImmutablePathMatcher used for public path matching.
 */
class PathMatcherPojoTest {

    // --- Helper: build a matcher from a list of patterns ---

    private ImmutablePathMatcher<Boolean> buildMatcher(String... patterns) {
        ImmutablePathMatcher.ImmutablePathMatcherBuilder<Boolean> builder = ImmutablePathMatcher.builder();
        for (String pattern : patterns) {
            builder.addPath(pattern, Boolean.TRUE);
        }
        return builder.build();
    }

    // --- Exact match ---

    @Test
    void testExactMatch() {
        ImmutablePathMatcher<Boolean> matcher = buildMatcher("/api/users");
        assertEquals(Boolean.TRUE, matcher.match("/api/users").getValue());
    }

    @Test
    void testExactMatch_NoMatchDifferentPath() {
        ImmutablePathMatcher<Boolean> matcher = buildMatcher("/api/users");
        assertNull(matcher.match("/api/roles").getValue());
    }

    // --- Double wildcard (**) ---

    @Test
    void testDoubleWildcard_MatchesChildPaths() {
        ImmutablePathMatcher<Boolean> matcher = buildMatcher("/api/public/*");
        assertEquals(Boolean.TRUE, matcher.match("/api/public/docs").getValue());
    }

    @Test
    void testDoubleWildcard_NoMatchForSiblingPath() {
        ImmutablePathMatcher<Boolean> matcher = buildMatcher("/api/public/*");
        assertNull(matcher.match("/api/private/docs").getValue());
    }

    // --- matchesAny equivalent using ImmutablePathMatcher ---

    @Test
    void testMatchesAny_MatchesOne() {
        ImmutablePathMatcher<Boolean> matcher = buildMatcher("/health", "/q/*", "/q/health");
        assertNotNull(matcher.match("/q/health").getValue());
    }

    @Test
    void testMatchesAny_MatchesNone() {
        ImmutablePathMatcher<Boolean> matcher = buildMatcher("/health", "/q/health", "/q/metrics");
        assertNull(matcher.match("/api/secret").getValue());
    }

    @Test
    void testMatchesAny_EmptyPatterns() {
        ImmutablePathMatcher<Boolean> matcher = buildMatcher();
        assertNull(matcher.match("/api/users").getValue());
    }

    // --- Internal path detection (logic moved from PathMatcher.isInternalPath) ---

    @Test
    void testIsInternalPath() {
        List<String> internalPaths = List.of(
                "/q/health", "/q/metrics", "/health", "/metrics", "/ready", "/live"
        );
        List<String> externalPaths = List.of("/api/data", "/public/test", "/");

        for (String path : internalPaths) {
            assertTrue(isInternalPath(path), "Expected internal: " + path);
        }
        for (String path : externalPaths) {
            assertFalse(isInternalPath(path), "Expected NOT internal: " + path);
        }
        assertFalse(isInternalPath(null));
        assertFalse(isInternalPath(""));
    }

    // --- Parametrized ---

    @ParameterizedTest
    @CsvSource({
            "/api/users, /api/users, true",
            "/api/other, /api/users, false",
    })
    void testPathMatching(String path, String pattern, boolean expected) {
        ImmutablePathMatcher<Boolean> matcher = buildMatcher(pattern);
        assertEquals(expected, matcher.match(path).getValue() == Boolean.TRUE,
                "Path '%s' should %smatch pattern '%s'".formatted(path, expected ? "" : "NOT ", pattern));
    }

    // Mirrors the logic in SidecarRequestProcessor
    private boolean isInternalPath(String path) {
        if (path == null) return false;
        return path.startsWith("/q/") ||
                path.equals("/health") ||
                path.equals("/metrics") ||
                path.equals("/ready") ||
                path.equals("/live");
    }
}
