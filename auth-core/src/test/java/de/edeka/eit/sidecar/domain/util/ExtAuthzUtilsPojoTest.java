package de.edeka.eit.sidecar.domain.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExtAuthzUtilsPojoTest {

    @Test
    void testIsInternalPath() {
        // Internal paths
        assertTrue(ExtAuthzUtils.isInternalPath("/q/health", "/q"));
        assertTrue(ExtAuthzUtils.isInternalPath("/q/metrics", "/q"));
        assertTrue(ExtAuthzUtils.isInternalPath("/health", "/q"));
        assertTrue(ExtAuthzUtils.isInternalPath("/metrics", "/q"));
        assertTrue(ExtAuthzUtils.isInternalPath("/ready", "/q"));
        assertTrue(ExtAuthzUtils.isInternalPath("/live", "/q"));

        // Regular paths (should NOT be internal)
        assertFalse(ExtAuthzUtils.isInternalPath("/api/v1/users", "/q"));
        assertFalse(ExtAuthzUtils.isInternalPath("/index.html", "/q"));
        assertFalse(ExtAuthzUtils.isInternalPath("/", "/q"));
        assertFalse(ExtAuthzUtils.isInternalPath("", "/q"));
        assertFalse(ExtAuthzUtils.isInternalPath(null, "/q"));
        
        // Edge cases
        assertFalse(ExtAuthzUtils.isInternalPath("/health-custom", "/q"));
        assertTrue(ExtAuthzUtils.isInternalPath("/q/test", "/q"));
        assertTrue(ExtAuthzUtils.isInternalPath("/custom/health", "/custom"));
    }
}
