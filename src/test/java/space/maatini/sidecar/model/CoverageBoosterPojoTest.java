package space.maatini.sidecar.model;

import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

public class CoverageBoosterPojoTest {

    @Test
    void testAuthContextBuilder() {
        AuthContext ctx = AuthContext.builder()
                .userId("u1")
                .email("e@e.com")
                .tenant("t1")
                .roles(Set.of("r1"))
                .permissions(Set.of("p1"))
                .build();

        assertEquals("u1", ctx.userId());
        assertEquals("e@e.com", ctx.email());
        assertEquals("t1", ctx.tenant());
        assertTrue(ctx.roles().contains("r1"));
        assertTrue(ctx.permissions().contains("p1"));
        assertTrue(ctx.isAuthenticated());
        assertTrue(ctx.hasRole("r1"));
        assertFalse(ctx.hasRole("r2"));
        assertTrue(ctx.hasAnyRole("r1", "other"));
        assertFalse(ctx.hasAnyRole("none"));
        assertTrue(ctx.hasAllRoles("r1"));
        assertFalse(ctx.hasAllRoles("r1", "r2"));
        assertTrue(ctx.hasPermission("p1"));
        assertFalse(ctx.hasPermission("p2"));

        AuthContext ctx2 = AuthContext.anonymous();
        assertFalse(ctx2.isAuthenticated());
        assertEquals("anonymous", ctx2.userId());
        assertFalse(ctx2.hasRole("any"));
        assertFalse(ctx2.hasAnyRole("any"));
        assertFalse(ctx2.hasAllRoles("any"));
        assertFalse(ctx2.hasPermission("any"));
    }

    @Test
    void testAuthContext_BuilderBranches() {
        AuthContext ctx = AuthContext.builder()
                .userId("u")
                .tenant("t")
                .issuer("i")
                .email("e")
                .preferredUsername("un")
                .build();
        assertTrue(ctx.roles().isEmpty());
        assertTrue(ctx.permissions().isEmpty());

        AuthContext ctx2 = AuthContext.builder()
                .roles(java.util.Set.of("a", "b"))
                .permissions(java.util.Set.of("p1", "p2"))
                .build();
        assertEquals(2, ctx2.roles().size());
        assertEquals(2, ctx2.permissions().size());
    }

    @Test
    void testPolicyDecision_Branches() {
        PolicyDecision d1 = PolicyDecision.allow();
        assertTrue(d1.allowed());

        PolicyDecision d2 = PolicyDecision.deny("reason");
        assertFalse(d2.allowed());
        assertEquals("reason", d2.reason());

        PolicyDecision d3 = PolicyDecision.builder().allowed(true).reason("why").build();
        assertTrue(d3.allowed());
        assertEquals("why", d3.reason());
    }

    @Test
    void testPolicyDecision() {
        PolicyDecision.Builder b = PolicyDecision.builder();
        b.allowed(false);
        b.reason("no");
        b.violations(List.of("1"));
        PolicyDecision d = b.build();
        assertFalse(d.allowed());
        assertEquals("no", d.reason());
        assertEquals(1, d.violations().size());
    }

    @Test
    void testPolicyInput() {
        AuthContext ctx = AuthContext.anonymous();
        PolicyInput in = PolicyInput.from(ctx, "GET", "/path", Map.of("Host", "localhost"), Map.of("x", "y"));

        assertEquals("GET", in.request().method());
        assertEquals("/path", in.request().path());
        assertEquals(1, in.request().pathSegments().length);
        assertNotNull(in.request().headers());
        assertNotNull(in.request().queryParams());

        PolicyInput.UserInfo ui = in.user();
        assertEquals("anonymous", ui.id());
        assertNull(ui.tenant());

        PolicyInput.ResourceInfo ri = in.resource();
        assertEquals("path", ri.type());
        assertNull(ri.id());
        assertNull(ri.action());

        // Context map
        assertEquals("rr-sidecar", in.context().get("source"));
    }
}
