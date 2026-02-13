package space.maatini.sidecar.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PathMatcher utility.
 */
class PathMatcherTest {

    // --- Exact match ---

    @Test
    void testExactMatch() {
        assertTrue(PathMatcher.matches("/api/users", "/api/users"));
    }

    @Test
    void testExactMatch_NoMatchDifferentPath() {
        assertFalse(PathMatcher.matches("/api/users", "/api/roles"));
    }

    @Test
    void testExactMatch_TrailingSlashNormalized() {
        assertTrue(PathMatcher.matches("/api/users/", "/api/users"));
    }

    // --- Double wildcard (**) ---

    @Test
    void testDoubleWildcard_MatchesSelf() {
        assertTrue(PathMatcher.matches("/api/public", "/api/public/**"));
    }

    @Test
    void testDoubleWildcard_MatchesChildPaths() {
        assertTrue(PathMatcher.matches("/api/public/docs", "/api/public/**"));
    }

    @Test
    void testDoubleWildcard_MatchesDeeplyNested() {
        assertTrue(PathMatcher.matches("/api/public/a/b/c", "/api/public/**"));
    }

    @Test
    void testDoubleWildcard_NoMatchForSiblingPath() {
        assertFalse(PathMatcher.matches("/api/private/docs", "/api/public/**"));
    }

    // --- Single wildcard (*) ---

    @Test
    void testSingleWildcard_MatchesOneSegment() {
        assertTrue(PathMatcher.matches("/api/users/123", "/api/users/*"));
    }

    @Test
    void testSingleWildcard_NoMatchForNestedPath() {
        assertFalse(PathMatcher.matches("/api/users/123/profile", "/api/users/*"));
    }

    @Test
    void testSingleWildcard_NoMatchForNoSegment() {
        assertFalse(PathMatcher.matches("/api/users", "/api/users/*"));
    }

    // --- Null handling ---

    @Test
    void testNullPath() {
        assertFalse(PathMatcher.matches(null, "/api/users"));
    }

    @Test
    void testNullPattern() {
        assertFalse(PathMatcher.matches("/api/users", null));
    }

    // --- matchesAny ---

    @Test
    void testMatchesAny_MatchesOne() {
        assertTrue(PathMatcher.matchesAny("/q/health",
                List.of("/health", "/q/health", "/q/metrics")));
    }

    @Test
    void testMatchesAny_MatchesNone() {
        assertFalse(PathMatcher.matchesAny("/api/secret",
                List.of("/health", "/q/health", "/q/metrics")));
    }

    @Test
    void testMatchesAny_MatchesWildcard() {
        assertTrue(PathMatcher.matchesAny("/api/public/doc",
                List.of("/health", "/api/public/**")));
    }

    @Test
    void testMatchesAny_NullPatterns() {
        assertFalse(PathMatcher.matchesAny("/api/users", null));
    }

    @Test
    void testMatchesAny_EmptyPatterns() {
        assertFalse(PathMatcher.matchesAny("/api/users", List.of()));
    }

    // --- Parametrized edge cases ---

    @ParameterizedTest
    @CsvSource({
            "/,              /,          true",
            "/api,           /api,       true",
            "/api/users/123, /api/**,    true",
            "/api,           /api/**,    true",
    })
    void testPathMatching(String path, String pattern, boolean expected) {
        assertEquals(expected, PathMatcher.matches(path, pattern),
                "Path '%s' should %smatch pattern '%s'"
                        .formatted(path, expected ? "" : "NOT ", pattern));
    }
}
