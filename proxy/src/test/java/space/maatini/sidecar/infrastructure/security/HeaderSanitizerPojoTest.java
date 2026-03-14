package space.maatini.sidecar.infrastructure.security;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * POJO-First tests for HeaderSanitizer.
 * Validates defense-in-depth against header-spoofing attacks.
 */
class HeaderSanitizerPojoTest {

    private HeaderSanitizer sanitizer;
    private HttpServerRequest request;

    @BeforeEach
    void setup() {
        sanitizer = new HeaderSanitizer();
        request = mock(HttpServerRequest.class);
        when(request.path()).thenReturn("/authorize");
        when(request.method()).thenReturn(HttpMethod.GET);
    }

    // --- extractPath tests ---

    @Test
    void extractPath_withValidEnvoyHeader_returnsHeaderValue() {
        when(request.getHeader("X-Envoy-Original-Path")).thenReturn("/api/users/123");

        assertEquals("/api/users/123", sanitizer.extractPath(request));
    }

    @Test
    void extractPath_withoutHeader_fallsBackToRequestPath() {
        when(request.getHeader("X-Envoy-Original-Path")).thenReturn(null);

        assertEquals("/authorize", sanitizer.extractPath(request));
    }

    @Test
    void extractPath_withEmptyHeader_fallsBackToRequestPath() {
        when(request.getHeader("X-Envoy-Original-Path")).thenReturn("  ");

        assertEquals("/authorize", sanitizer.extractPath(request));
    }

    @Test
    void extractPath_withPathTraversal_normalizesSegments() {
        when(request.getHeader("X-Envoy-Original-Path")).thenReturn("/api/public/../../admin/secret");

        String result = sanitizer.extractPath(request);
        assertFalse(result.contains(".."), "Path traversal segments should be removed");
        assertEquals("/admin/secret", result);
    }

    @Test
    void extractPath_withDoubleSlashes_collapses() {
        when(request.getHeader("X-Envoy-Original-Path")).thenReturn("//admin//panel");

        assertEquals("/admin/panel", sanitizer.extractPath(request));
    }

    @Test
    void extractPath_withUrlEncodedTraversal_decodesAndNormalizes() {
        when(request.getHeader("X-Envoy-Original-Path")).thenReturn("/api/%2e%2e/admin");

        String result = sanitizer.extractPath(request);
        assertFalse(result.contains(".."), "URL-encoded traversal should be decoded and removed");
        assertEquals("/admin", result);
    }

    @Test
    void extractPath_withQueryString_preservesQuery() {
        when(request.getHeader("X-Envoy-Original-Path")).thenReturn("/api/data?page=1&size=10");

        assertEquals("/api/data?page=1&size=10", sanitizer.extractPath(request));
    }

    @Test
    void extractPath_withQueryStringAndTraversal_normalizesPathPreservesQuery() {
        when(request.getHeader("X-Envoy-Original-Path")).thenReturn("/api/../admin?page=1");

        assertEquals("/admin?page=1", sanitizer.extractPath(request));
    }

    @Test
    void extractPath_traversalAboveRoot_stopsAtRoot() {
        when(request.getHeader("X-Envoy-Original-Path")).thenReturn("/api/../../../../etc/passwd");

        String result = sanitizer.extractPath(request);
        assertEquals("/etc/passwd", result);
        // Traversal above root is clamped – never reaches filesystem
    }

    // --- extractMethod tests ---

    @Test
    void extractMethod_withValidHeader_returnsUppercaseMethod() {
        when(request.getHeader("X-Forwarded-Method")).thenReturn("post");

        assertEquals("POST", sanitizer.extractMethod(request));
    }

    @Test
    void extractMethod_withoutHeader_fallsBackToRequestMethod() {
        when(request.getHeader("X-Forwarded-Method")).thenReturn(null);

        assertEquals("GET", sanitizer.extractMethod(request));
    }

    @Test
    void extractMethod_withInvalidMethod_fallsBackToRequestMethod() {
        when(request.getHeader("X-Forwarded-Method")).thenReturn("HACK");

        assertEquals("GET", sanitizer.extractMethod(request));
    }

    @Test
    void extractMethod_withEmptyHeader_fallsBackToRequestMethod() {
        when(request.getHeader("X-Forwarded-Method")).thenReturn("  ");

        assertEquals("GET", sanitizer.extractMethod(request));
    }

    @ParameterizedTest
    @CsvSource({"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"})
    void extractMethod_allValidMethodsAccepted(String method) {
        when(request.getHeader("X-Forwarded-Method")).thenReturn(method);

        assertEquals(method, sanitizer.extractMethod(request));
    }

    // --- isEnvoyInternalHeader tests ---

    @Test
    void isEnvoyInternalHeader_recognizesKnownHeaders() {
        assertTrue(sanitizer.isEnvoyInternalHeader("x-envoy-original-path"));
        assertTrue(sanitizer.isEnvoyInternalHeader("x-forwarded-method"));
        assertTrue(sanitizer.isEnvoyInternalHeader("x-forwarded-uri"));
        assertTrue(sanitizer.isEnvoyInternalHeader("x-envoy-internal"));
        assertTrue(sanitizer.isEnvoyInternalHeader("x-envoy-decorator-operation"));
        assertTrue(sanitizer.isEnvoyInternalHeader("x-envoy-expected-rq-timeout-ms"));
        assertTrue(sanitizer.isEnvoyInternalHeader("x-envoy-original-method"));
    }

    @Test
    void isEnvoyInternalHeader_doesNotMatchRegularHeaders() {
        assertFalse(sanitizer.isEnvoyInternalHeader("authorization"));
        assertFalse(sanitizer.isEnvoyInternalHeader("content-type"));
        assertFalse(sanitizer.isEnvoyInternalHeader("x-custom-header"));
    }

    // --- normalizePath static tests ---

    @ParameterizedTest
    @CsvSource({
            "'/api/data', '/api/data'",
            "'/', '/'",
            "'', '/'",
            "'///', '/'",
            "'/a/../b', '/b'",
            "'/a/./b', '/a/b'",
    })
    void normalizePath_handlesEdgeCases(String input, String expected) {
        assertEquals(expected, HeaderSanitizer.normalizePath(input));
    }
}
