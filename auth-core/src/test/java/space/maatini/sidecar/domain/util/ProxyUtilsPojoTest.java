package space.maatini.sidecar.domain.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProxyUtilsPojoTest {

    @Test
    void testIsInternalPath() {
        // Internal paths
        assertTrue(ProxyUtils.isInternalPath("/q/health"));
        assertTrue(ProxyUtils.isInternalPath("/q/metrics"));
        assertTrue(ProxyUtils.isInternalPath("/health"));
        assertTrue(ProxyUtils.isInternalPath("/metrics"));
        assertTrue(ProxyUtils.isInternalPath("/ready"));
        assertTrue(ProxyUtils.isInternalPath("/live"));

        // Regular paths (should NOT be internal)
        assertFalse(ProxyUtils.isInternalPath("/api/v1/users"));
        assertFalse(ProxyUtils.isInternalPath("/index.html"));
        assertFalse(ProxyUtils.isInternalPath("/"));
        assertFalse(ProxyUtils.isInternalPath(""));
        assertFalse(ProxyUtils.isInternalPath(null));
        
        // Edge cases
        assertFalse(ProxyUtils.isInternalPath("/health-custom"));
        assertFalse(ProxyUtils.isInternalPath("/q")); // Should probably be true if we want to block all /q/*
    }
}
