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

@io.quarkus.test.junit.QuarkusTest
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
        void testResolvePropagatedHeaders_MatchesCaseInsensitive() {
                ProxyService service = new ProxyService();
                service.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig proxyConfig = mock(SidecarConfig.ProxyConfig.class);
                when(service.config.proxy()).thenReturn(proxyConfig);
                when(proxyConfig.propagateHeaders()).thenReturn(List.of("important-header"));

                // Use different case
                Map<String, String> headers = Map.of("IMPORTANT-HEADER", "value123", "content-type", "application/json",
                                "accept", "text/html");

                Map<String, String> result = service.resolvePropagatedHeaders(headers);

                assertEquals("value123", result.get("important-header"));
                assertEquals("application/json", result.get("Content-Type"));
                assertEquals("text/html", result.get("Accept"));
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
}
