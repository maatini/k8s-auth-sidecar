package space.maatini.sidecar.common.model;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ModelPojoTest {

        @Test
        void testAuthContext_Anonymous() {
                AuthContext ctx = AuthContext.anonymous();
                assertFalse(ctx.isAuthenticated());
                assertEquals("anonymous", ctx.userId());
                assertTrue(ctx.roles().isEmpty());
        }

        @Test
        void testAuthContext_Builder() {
                AuthContext ctx = AuthContext.builder()
                                .userId("u1")
                                .email("u1@ex.co")
                                .roles(Set.of("admin", "user"))
                                .permissions(Set.of("read", "write"))
                                .issuer("iss")
                                .expiresAt(System.currentTimeMillis() / 1000 + 3600)
                                .build();

                assertTrue(ctx.isAuthenticated());
                assertEquals("u1", ctx.userId());
                assertTrue(ctx.hasRole("admin"));
                assertFalse(ctx.hasRole("guest"));
                assertTrue(ctx.hasPermission("read"));
                assertFalse(ctx.hasPermission("delete"));
                assertFalse(ctx.isExpired());

                // Testing hasAnyRole
                assertTrue(ctx.hasAnyRole("admin", "guest"));
                assertTrue(ctx.hasAnyRole("guest", "user"));
                assertFalse(ctx.hasAnyRole("guest", "viewer"));
                assertFalse(ctx.hasAnyRole()); // empty varargs

                // Testing hasAllRoles
                assertTrue(ctx.hasAllRoles("admin", "user"));
                assertFalse(ctx.hasAllRoles("admin", "guest"));
                assertTrue(ctx.hasAllRoles()); // empty varargs is vacuously true
        }

        @Test
        void testAuthContext_NullEdges() {
                AuthContext ctx = AuthContext.builder().roles(null).permissions(null).claims(null).audience(null)
                                .build();
                assertFalse(ctx.hasRole("any"));
                assertFalse(ctx.hasAnyRole("any"));
                assertFalse(ctx.hasAllRoles("any"));
                assertFalse(ctx.hasPermission("any"));
                assertTrue(ctx.getClaim("any").isEmpty());
                assertTrue(ctx.audience().isEmpty());

                AuthContext withRoles = ctx.withRolesAndPermissions(null, null);
                assertTrue(withRoles.roles().isEmpty());
                assertTrue(withRoles.permissions().isEmpty());
        }

        @Test
        void testAuthContext_Expiration() {
                long now = System.currentTimeMillis() / 1000;
                AuthContext expired = AuthContext.builder().expiresAt(now - 10).build();
                assertTrue(expired.isExpired());

                AuthContext notExpired = AuthContext.builder().expiresAt(now + 10).build();
                assertFalse(notExpired.isExpired());

                AuthContext noExpiration = AuthContext.builder().expiresAt(0).build();
                assertFalse(noExpiration.isExpired());
        }

        @Test
        void testPolicyInput_From() {
                AuthContext auth = AuthContext.builder()
                                .userId("u1")
                                .email("u@e.c")
                                .roles(Set.of("r1"))
                                .build();

                PolicyInput input = PolicyInput.from(auth, "POST", "/api/v1/users/123",
                                Map.of("X-H1", "v1"), Map.of("p1", "v1"));

                assertEquals("POST", input.request().method());
                assertEquals("/api/v1/users/123", input.request().path());
                assertArrayEquals(new String[] { "api", "v1", "users", "123" }, input.request().pathSegments());
                assertEquals("u1", input.user().id());
                assertEquals("u@e.c", input.user().email());
                assertTrue(input.user().roles().contains("r1"));

                // Resource extraction
                assertEquals("users", input.resource().type());
                assertEquals("123", input.resource().id());
        }

        @Test
        void testPolicyInput_PathSegments_NullOrEmpty() {
                PolicyInput.RequestInfo req1 = new PolicyInput.RequestInfo("GET", null, null, null);
                assertEquals(0, req1.pathSegments().length);

                PolicyInput.RequestInfo req2 = new PolicyInput.RequestInfo("GET", "", null, null);
                assertEquals(0, req2.pathSegments().length);

                PolicyInput.ResourceInfo res1 = PolicyInput.ResourceInfo.fromPath(null);
                assertNull(res1.type());
                assertNull(res1.id());

                PolicyInput.ResourceInfo res2 = PolicyInput.ResourceInfo.fromPath("");
                assertNull(res2.type());
        }
}
