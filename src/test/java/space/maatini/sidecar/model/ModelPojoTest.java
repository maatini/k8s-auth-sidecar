package space.maatini.sidecar.model;

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
                                .roles(Set.of("admin"))
                                .issuer("iss")
                                .build();

                assertTrue(ctx.isAuthenticated());
                assertEquals("u1", ctx.userId());
                assertTrue(ctx.hasRole("admin"));
                assertFalse(ctx.hasRole("user"));
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
                assertEquals("u1", input.user().id());
                assertEquals("u@e.c", input.user().email());
                assertTrue(input.user().roles().contains("r1"));

                // Resource extraction
                assertEquals("users", input.resource().type());
                assertEquals("123", input.resource().id());
        }
}
