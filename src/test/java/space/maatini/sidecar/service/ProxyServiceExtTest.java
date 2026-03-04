package space.maatini.sidecar.service;

import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.config.SidecarConfig;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProxyServiceExtTest {

        @Test
        void testProxyResponseIsSuccess() throws Exception {
                Class<?> proxyResponseClass = Class.forName("space.maatini.sidecar.service.ProxyService$ProxyResponse");

                Method errorMethod = proxyResponseClass.getDeclaredMethod("error", int.class, String.class);
                errorMethod.setAccessible(true);

                Object response200 = proxyResponseClass
                                .getDeclaredConstructor(int.class, String.class, Map.class, Buffer.class)
                                .newInstance(200, "OK", Map.of(), null);
                Object response299 = proxyResponseClass
                                .getDeclaredConstructor(int.class, String.class, Map.class, Buffer.class)
                                .newInstance(299, "OK", Map.of(), null);
                Object response300 = proxyResponseClass
                                .getDeclaredConstructor(int.class, String.class, Map.class, Buffer.class)
                                .newInstance(300, "OK", Map.of(), null);

                Method isSuccessMethod = proxyResponseClass.getDeclaredMethod("isSuccess");

                assertTrue((Boolean) isSuccessMethod.invoke(response200));
                assertTrue((Boolean) isSuccessMethod.invoke(response299));
                assertFalse((Boolean) isSuccessMethod.invoke(response300));

                Object errorResponse = errorMethod.invoke(null, 500, "Internal Server Error");
                assertFalse((Boolean) isSuccessMethod.invoke(errorResponse));
        }

        @Test
        void testProxyResponseHeaders() throws Exception {
                Class<?> proxyResponseClass = Class.forName("space.maatini.sidecar.service.ProxyService$ProxyResponse");

                Map<String, String> testHeaders = Map.of("content-type", "application/json", "x-custom", "value");
                Object response = proxyResponseClass
                                .getDeclaredConstructor(int.class, String.class, Map.class, Buffer.class)
                                .newInstance(200, "OK", testHeaders, Buffer.buffer("test"));

                Method headersMethod = proxyResponseClass.getDeclaredMethod("headers");
                Map<String, String> returnedHeaders = (Map<String, String>) headersMethod.invoke(response);
                assertEquals(2, returnedHeaders.size());

                Method bodyAsStringMethod = proxyResponseClass.getDeclaredMethod("bodyAsString");
                assertEquals("test", bodyAsStringMethod.invoke(response));

                // Test null body bodyAsString fallback
                Object responseNullBody = proxyResponseClass
                                .getDeclaredConstructor(int.class, String.class, Map.class, Buffer.class)
                                .newInstance(200, "OK", testHeaders, null);
                assertEquals("", bodyAsStringMethod.invoke(responseNullBody));
        }

        @Test
        void testResolvePropagatedHeaders_NullMap() {
                ProxyService service = new ProxyService();
                service.config = mock(SidecarConfig.class);
                Map<String, String> result = service.resolvePropagatedHeaders(null);
                assertTrue(result.isEmpty());
        }

        @Test
        void testResolvePropagatedHeaders_Full() {
                ProxyService service = new ProxyService();
                service.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig proxyConfig = mock(SidecarConfig.ProxyConfig.class);
                when(service.config.proxy()).thenReturn(proxyConfig);

                // One matches, one doesnt match propagation list
                when(proxyConfig.propagateHeaders()).thenReturn(List.of("X-Trace-Id"));

                Map<String, String> headers = Map.of(
                                "x-trace-id", "trace-123",
                                "x-not-propagated", "gone",
                                "Content-Type", "application/json");

                Map<String, String> result = service.resolvePropagatedHeaders(headers);

                // Verify specific mappings and preservation of case/keys
                assertEquals("trace-123", result.get("X-Trace-Id")); // Key from propagateList
                assertEquals("application/json", result.get("Content-Type"));
                assertNull(result.get("x-not-propagated"));
                assertEquals(2, result.size());

                // Mutation killer: check if changing mapped value survives
                assertNotEquals("wrong", result.get("X-Trace-Id"));
        }

        @Test
        void testResolvePropagatedHeaders_EmptyList() {
                ProxyService service = new ProxyService();
                service.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig proxyConfig = mock(SidecarConfig.ProxyConfig.class);
                when(service.config.proxy()).thenReturn(proxyConfig);
                when(proxyConfig.propagateHeaders()).thenReturn(List.of());

                Map<String, String> headers = Map.of("X-Trace-Id", "123");
                Map<String, String> result = service.resolvePropagatedHeaders(headers);
                assertTrue(result.isEmpty());
        }

        @Test
        void testResolvePlaceholders_SupportedVariables() {
                ProxyService service = new ProxyService();
                AuthContext ctx = AuthContext.builder()
                                .userId("u1")
                                .email("e1")
                                .name("n1")
                                .roles(java.util.Set.of("r1"))
                                .build();

                String template = "${user.id}|${user.email}|${user.name}|${user.roles}";
                String expected = "u1|e1|n1|r1";
                assertEquals(expected, service.resolvePlaceholders(template, ctx));
        }

        @Test
        void testResolvePlaceholders_Nulls() {
                ProxyService service = new ProxyService();
                AuthContext ctx = AuthContext.builder().userId("user1").build();

                assertNull(service.resolvePlaceholders(null, ctx));

                String resolvedContent = service.resolvePlaceholders("Hello ${user.name}", ctx);
                assertEquals("Hello ", resolvedContent); // null name replaced with empty string
        }

        @Test
        void testResolveAuthContextHeaders_NullOrAnonymous() {
                ProxyService service = new ProxyService();

                Map<String, String> map1 = service.resolveAuthContextHeaders(null);
                assertTrue(map1.isEmpty());

                Map<String, String> map2 = service.resolveAuthContextHeaders(AuthContext.anonymous());
                assertTrue(map2.isEmpty());
        }

        @Test
        void testResolveAuthContextHeaders_DefaultHeaders() {
                ProxyService service = new ProxyService();
                service.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig proxyConfig = mock(SidecarConfig.ProxyConfig.class);
                when(service.config.proxy()).thenReturn(proxyConfig);
                when(proxyConfig.addHeaders()).thenReturn(java.util.Map.of());

                AuthContext ctx = AuthContext.builder()
                                .userId("user123")
                                .email("dummy@user.com")
                                .roles(java.util.Set.of("role-a", "role-b"))
                                .build();

                Map<String, String> headers = service.resolveAuthContextHeaders(ctx);
                assertEquals("user123", headers.get("X-Auth-User-Id"));
                assertEquals("dummy@user.com", headers.get("X-Auth-User-Email"));
                assertTrue(headers.get("X-Auth-User-Roles").contains("role-a"));
        }

        @Test
        void testResolveAuthContextHeaders_CustomHeaders() {
                ProxyService service = new ProxyService();
                service.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig proxyConfig = mock(SidecarConfig.ProxyConfig.class);
                when(service.config.proxy()).thenReturn(proxyConfig);
                when(proxyConfig.addHeaders()).thenReturn(Map.of(
                                "X-Target-User", "${user.id}",
                                "X-Empty", "${unknown}"));

                AuthContext ctx = AuthContext.builder()
                                .userId("user123")
                                .build();

                Map<String, String> headers = service.resolveAuthContextHeaders(ctx);
                assertEquals("user123", headers.get("X-Target-User"));
                assertEquals("${unknown}", headers.get("X-Empty"));
        }

        @Test
        void testResolveAuthContextHeaders_Complex() {
                ProxyService service = new ProxyService();
                service.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig proxyConfig = mock(SidecarConfig.ProxyConfig.class);
                when(service.config.proxy()).thenReturn(proxyConfig);

                // Add headers with mix of standard and custom placeholders
                when(proxyConfig.addHeaders()).thenReturn(Map.of(
                                "X-User-ID", "${user.id}",
                                "X-User-Email", "${user.email}",
                                "X-User-Name", "${user.name}",
                                "X-User-Roles", "${user.roles}",
                                "X-Static", "static-val"));

                AuthContext ctx = AuthContext.builder()
                                .userId("u1")
                                .email("e1")
                                .name("n1")
                                .roles(java.util.Set.of("r1", "r2"))
                                .build();

                Map<String, String> headers = service.resolveAuthContextHeaders(ctx);
                assertEquals("u1", headers.get("X-User-ID"));
                assertEquals("e1", headers.get("X-User-Email"));
                assertEquals("n1", headers.get("X-User-Name"));
                assertTrue(headers.get("X-User-Roles").contains("r1"));
                assertTrue(headers.get("X-User-Roles").contains("r2"));
                assertEquals("static-val", headers.get("X-Static"));

                // Default headers should NOT be present if custom are specified
                assertNull(headers.get("X-Auth-User-Id"));
        }

        @Test
        void testResolvePropagatedHeaders_CaseInsensitive() {
                ProxyService service = new ProxyService();
                service.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig proxyConfig = mock(SidecarConfig.ProxyConfig.class);
                when(service.config.proxy()).thenReturn(proxyConfig);

                when(proxyConfig.propagateHeaders()).thenReturn(List.of("X-TRACE-ID"));

                Map<String, String> headers = Map.of("x-trace-id", "val123");
                Map<String, String> result = service.resolvePropagatedHeaders(headers);

                assertEquals("val123", result.get("X-TRACE-ID"));
                assertEquals(1, result.size());
        }

        @Test
        void testResolvePropagatedHeaders_Strict() {
                ProxyService service = new ProxyService();
                service.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig proxyConfig = mock(SidecarConfig.ProxyConfig.class);
                when(service.config.proxy()).thenReturn(proxyConfig);

                when(proxyConfig.propagateHeaders()).thenReturn(List.of("X-Required"));

                Map<String, String> headers = Map.of("X-Required", "present", "X-Other", "ignore");
                Map<String, String> result = service.resolvePropagatedHeaders(headers);

                assertEquals("present", result.get("X-Required"));
                assertNull(result.get("X-Other"));
                assertEquals(1, result.size());
        }

        @Test
        void testResolvePropagatedHeaders_NullInput() {
                ProxyService service = new ProxyService();
                assertTrue(service.resolvePropagatedHeaders(null).isEmpty());
        }

        @Test
        void testCalculateDuration_MutationKiller() {
                ProxyService service = new ProxyService();
                long start = System.nanoTime();
                long dur = service.calculateDuration(start);
                // If mutated to (+), dur would be ~2 * now (huge).
                // If original (-), dur is ~small.
                assertTrue(dur >= 0, "Duration must be positive");
                assertTrue(dur < 10_000_000_000L, "Duration should be relatively small, not (now + start)");
        }
}
