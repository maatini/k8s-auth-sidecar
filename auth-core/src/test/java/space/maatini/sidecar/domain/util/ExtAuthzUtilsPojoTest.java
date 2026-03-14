package space.maatini.sidecar.domain.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProxyUtilsPojoTest {

    @Test
    void testIsInternalPath() {
        // Internal paths
        assertTrue(ProxyUtils.isInternalPath("/q/health", "/q"));
        assertTrue(ProxyUtils.isInternalPath("/q/metrics", "/q"));
        assertTrue(ProxyUtils.isInternalPath("/health", "/q"));
        assertTrue(ProxyUtils.isInternalPath("/metrics", "/q"));
        assertTrue(ProxyUtils.isInternalPath("/ready", "/q"));
        assertTrue(ProxyUtils.isInternalPath("/live", "/q"));

        // Regular paths (should NOT be internal)
        assertFalse(ProxyUtils.isInternalPath("/api/v1/users", "/q"));
        assertFalse(ProxyUtils.isInternalPath("/index.html", "/q"));
        assertFalse(ProxyUtils.isInternalPath("/", "/q"));
        assertFalse(ProxyUtils.isInternalPath("", "/q"));
        assertFalse(ProxyUtils.isInternalPath(null, "/q"));
        
        // Edge cases
        assertFalse(ProxyUtils.isInternalPath("/health-custom", "/q"));
        assertTrue(ProxyUtils.isInternalPath("/q/test", "/q"));
        assertTrue(ProxyUtils.isInternalPath("/custom/health", "/custom"));
    }
}
