package space.maatini.sidecar.service;

import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.config.SidecarConfig;

import java.lang.reflect.Method;
import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProxyServiceExtTest {

        // ── ProxyResponse record tests ──

        @Test
        void testProxyResponseIsSuccess() throws Exception {
                Class<?> cls = Class.forName("space.maatini.sidecar.service.ProxyService$ProxyResponse");
                Constructor<?> ctor = cls.getDeclaredConstructor(int.class, String.class, Map.class, Buffer.class);
                ctor.setAccessible(true);
                Method isSuccess = cls.getDeclaredMethod("isSuccess");

                assertTrue((Boolean) isSuccess.invoke(ctor.newInstance(200, "OK", Map.of(), null)));
                assertTrue((Boolean) isSuccess.invoke(ctor.newInstance(299, "OK", Map.of(), null)));
                assertFalse((Boolean) isSuccess.invoke(ctor.newInstance(300, "OK", Map.of(), null)));
                assertFalse((Boolean) isSuccess.invoke(ctor.newInstance(199, "OK", Map.of(), null)));
        }

        @Test
        void testProxyResponseHeaders() throws Exception {
                Class<?> cls = Class.forName("space.maatini.sidecar.service.ProxyService$ProxyResponse");
                Constructor<?> ctor = cls.getDeclaredConstructor(int.class, String.class, Map.class, Buffer.class);
                ctor.setAccessible(true);

                Map<String, String> h = Map.of("content-type", "application/json", "x-custom", "value");
                Object resp = ctor.newInstance(200, "OK", h, Buffer.buffer("test"));

                Method headers = cls.getDeclaredMethod("headers");
                Method bodyAsString = cls.getDeclaredMethod("bodyAsString");
                assertEquals(2, ((Map<?, ?>) headers.invoke(resp)).size());
                assertEquals("test", bodyAsString.invoke(resp));

                // Null body → empty string
                Object respNull = ctor.newInstance(200, "OK", h, null);
                assertEquals("", bodyAsString.invoke(respNull));
        }

        @Test
        void testProxyResponse_ErrorSanitization() {
                ProxyService.ProxyResponse resp = ProxyService.ProxyResponse.error(500, "Error with \"quotes\"");
                assertTrue(resp.bodyAsString().contains("Error with \\\"quotes\\\""));

                ProxyService.ProxyResponse respNull = ProxyService.ProxyResponse.error(500, null);
                assertTrue(respNull.bodyAsString().contains("Internal error"));
        }

        // ── resolvePropagatedHeaders tests ──

        @Test
        void testResolvePropagatedHeaders_NullInput() {
                ProxyService s = new ProxyService();
                assertTrue(s.resolvePropagatedHeaders(null).isEmpty());
        }

        @Test
        void testResolvePropagatedHeaders_EmptyList() {
                ProxyService s = new ProxyService();
                s.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
                when(s.config.proxy()).thenReturn(pc);
                when(pc.propagateHeaders()).thenReturn(List.of());

                Map<String, String> r = s.resolvePropagatedHeaders(Map.of("X-Trace-Id", "123"));
                assertTrue(r.isEmpty());
        }

        @Test
        void testResolvePropagatedHeaders_Full() {
                ProxyService s = new ProxyService();
                s.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
                when(s.config.proxy()).thenReturn(pc);
                when(pc.propagateHeaders()).thenReturn(List.of("X-Trace-Id"));

                Map<String, String> input = Map.of("x-trace-id", "trace-123", "x-not-propagated", "gone",
                                "Content-Type", "application/json");
                Map<String, String> r = s.resolvePropagatedHeaders(input);

                assertEquals("trace-123", r.get("X-Trace-Id"));
                assertEquals("application/json", r.get("Content-Type"));
                assertNull(r.get("x-not-propagated"));
                assertEquals(2, r.size());
                assertNotEquals("wrong", r.get("X-Trace-Id"));
        }

        @Test
        void testResolvePropagatedHeaders_CaseInsensitive() {
                ProxyService s = new ProxyService();
                s.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
                when(s.config.proxy()).thenReturn(pc);
                when(pc.propagateHeaders()).thenReturn(List.of("X-TRACE-ID"));

                assertEquals("val123", s.resolvePropagatedHeaders(Map.of("x-trace-id", "val123")).get("X-TRACE-ID"));
        }

        @Test
        void testResolvePropagatedHeaders_NullValue() {
                ProxyService s = new ProxyService();
                s.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
                when(s.config.proxy()).thenReturn(pc);
                when(pc.propagateHeaders()).thenReturn(List.of("X-Trace-Id"));

                java.util.HashMap<String, String> input = new java.util.HashMap<>();
                input.put("x-trace-id", null);
                assertFalse(s.resolvePropagatedHeaders(input).containsKey("X-Trace-Id"));
        }

        @Test
        void testResolvePropagatedHeaders_Strict() {
                ProxyService s = new ProxyService();
                s.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
                when(s.config.proxy()).thenReturn(pc);
                when(pc.propagateHeaders()).thenReturn(List.of("X-Required"));

                Map<String, String> r = s
                                .resolvePropagatedHeaders(Map.of("X-Required", "present", "X-Other", "ignore"));
                assertEquals("present", r.get("X-Required"));
                assertNull(r.get("X-Other"));
                assertEquals(1, r.size());
        }

        // ── resolvePlaceholders tests ──

        @Test
        void testResolvePlaceholders_SupportedVariables() {
                ProxyService s = new ProxyService();
                AuthContext ctx = AuthContext.builder().userId("u1").email("e1").name("n1")
                                .roles(java.util.Set.of("r1")).build();
                assertEquals("u1|e1|n1|r1",
                                s.resolvePlaceholders("${user.id}|${user.email}|${user.name}|${user.roles}", ctx));
        }

        @Test
        void testResolvePlaceholders_Nulls() {
                ProxyService s = new ProxyService();
                AuthContext ctx = AuthContext.builder()
                                .userId("u1")
                                .name(null)
                                .email(null)
                                .roles(null)
                                .build();
                assertNull(s.resolvePlaceholders(null, ctx));

                // Ensure the placeholders for null values are replaced with empty strings
                // properly avoiding NPE natively
                assertEquals("Hello u1|||", s
                                .resolvePlaceholders("Hello ${user.id}|${user.email}|${user.name}|${user.roles}", ctx));
        }

        // ── resolveAuthContextHeaders tests ──

        @Test
        void testResolveAuthContextHeaders_NullOrAnonymous() {
                ProxyService s = new ProxyService();
                Map<String, String> map1 = s.resolveAuthContextHeaders(null);
                assertTrue(map1.isEmpty());
                assertEquals(java.util.HashMap.class, map1.getClass()); // kill emptyMap mutant

                Map<String, String> map2 = s.resolveAuthContextHeaders(AuthContext.anonymous());
                assertTrue(map2.isEmpty());
                assertEquals(java.util.HashMap.class, map2.getClass()); // kill emptyMap mutant
        }

        @Test
        void testResolveAuthContextHeaders_DefaultHeaders() {
                ProxyService s = new ProxyService();
                s.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
                when(s.config.proxy()).thenReturn(pc);
                when(pc.addHeaders()).thenReturn(Map.of());

                AuthContext ctx = AuthContext.builder().userId("u1").email("e@e").roles(java.util.Set.of("r-a", "r-b"))
                                .build();
                Map<String, String> h = s.resolveAuthContextHeaders(ctx);
                assertEquals("u1", h.get("X-Auth-User-Id"));
                assertEquals("e@e", h.get("X-Auth-User-Email"));
                assertTrue(h.get("X-Auth-User-Roles").contains("r-a"));

                // test explicitly when email is null and roles are explicitly null, killing the
                // 'removed conditional' mutants
                AuthContext ctxNulls = AuthContext.builder().userId("u2").email(null).roles(null).build();
                Map<String, String> hNulls = s.resolveAuthContextHeaders(ctxNulls);
                assertEquals("u2", hNulls.get("X-Auth-User-Id"));
                assertFalse(hNulls.containsKey("X-Auth-User-Email"));
                assertFalse(hNulls.containsKey("X-Auth-User-Roles"));
        }
        // ── resolvePropagatedHeaders tests ──

        @Test
        void testResolvePropagatedHeaders() {
                ProxyService s = new ProxyService();
                s.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
                when(s.config.proxy()).thenReturn(pc);

                // Assert null and empty
                assertTrue(s.resolvePropagatedHeaders(null).isEmpty());
                assertTrue(s.resolvePropagatedHeaders(Map.of()).isEmpty());

                // Set up valid propagate headers
                when(pc.propagateHeaders()).thenReturn(java.util.List.of("X-Forward", "X-Missing"));

                Map<String, String> incoming = Map.of("x-forward", "val-a", "x-other", "val-b");
                Map<String, String> resolved = s.resolvePropagatedHeaders(incoming);

                assertEquals(1, resolved.size());
                assertEquals("val-a", resolved.get("X-Forward"));
                assertFalse(resolved.containsKey("X-Missing")); // value was null/empty for X-Missing in incoming
        }

        @Test
        void testResolveAuthContextHeaders_CustomHeaders() {
                ProxyService s = new ProxyService();
                s.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
                when(s.config.proxy()).thenReturn(pc);
                when(pc.addHeaders()).thenReturn(Map.of("X-Target-User", "${user.id}", "X-Empty", "${unknown}"));

                AuthContext ctx = AuthContext.builder().userId("u1").build();
                Map<String, String> h = s.resolveAuthContextHeaders(ctx);
                assertEquals("u1", h.get("X-Target-User"));
                assertEquals("${unknown}", h.get("X-Empty"));
        }

        @Test
        void testResolveAuthContextHeaders_Complex() {
                ProxyService s = new ProxyService();
                s.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
                when(s.config.proxy()).thenReturn(pc);
                when(pc.addHeaders()).thenReturn(Map.of(
                                "X-User-ID", "${user.id}", "X-User-Email", "${user.email}",
                                "X-User-Name", "${user.name}", "X-User-Roles", "${user.roles}", "X-Static", "val"));

                AuthContext ctx = AuthContext.builder().userId("u1").email("e1").name("n1")
                                .roles(java.util.Set.of("r1", "r2")).build();
                Map<String, String> h = s.resolveAuthContextHeaders(ctx);
                assertEquals("u1", h.get("X-User-ID"));
                assertEquals("e1", h.get("X-User-Email"));
                assertEquals("val", h.get("X-Static"));
                assertNull(h.get("X-Auth-User-Id")); // custom overrides default
        }

        // ── calculateDuration / recordRequestMetrics tests ──

        @Test
        void testCalculateDuration() {
                ProxyService s = new ProxyService();
                long start = System.nanoTime() - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100);
                long dur = s.calculateDuration(start);
                // pitest replaces duration returning 0. So dur should be >= 100 to kill it.
                // pitest also replaces subtraction with addition. Then dur is massive.
                assertTrue(dur >= 90_000_000L && dur < 5_000_000_000L, "Duration out of logical bounds: " + dur);
        }

        @Test
        void testBuildTargetUrl() {
                ProxyService s = new ProxyService();
                s.config = mock(SidecarConfig.class);
                SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
                SidecarConfig.ProxyConfig.TargetConfig tc = mock(SidecarConfig.ProxyConfig.TargetConfig.class);
                when(s.config.proxy()).thenReturn(pc);
                when(pc.target()).thenReturn(tc);
                when(tc.scheme()).thenReturn("https");
                when(tc.host()).thenReturn("example.com");
                when(tc.port()).thenReturn(443);

                String url = s.buildTargetUrl("/api/v1");
                assertEquals("https://example.com:443/api/v1", url);
        }

        @Test
        void testRecordRequestMetrics() {
                ProxyService s = new ProxyService();
                io.micrometer.core.instrument.Timer t = mock(io.micrometer.core.instrument.Timer.class);
                s.requestTimer = t;

                s.recordRequestMetrics(System.nanoTime() - 1000, 200);
                verify(t, times(1)).record(anyLong(), eq(java.util.concurrent.TimeUnit.NANOSECONDS));

                s.recordRequestMetrics(System.nanoTime() - 1000, 500);
                verify(t, times(2)).record(anyLong(), eq(java.util.concurrent.TimeUnit.NANOSECONDS));

                ProxyService sNull = new ProxyService();
                sNull.requestTimer = null;
                // should not throw NPE, killing the "condition removed" mutant
                sNull.recordRequestMetrics(System.nanoTime() - 1000, 200);
        }

        // ── toProxyResponse test ──

        @Test
        void testToProxyResponse() {
                ProxyService s = new ProxyService();
                @SuppressWarnings("unchecked")
                HttpResponse<Buffer> mockResp = mock(HttpResponse.class);
                io.vertx.mutiny.core.MultiMap mm = io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap();
                mm.add("X-Test", "Value1");

                when(mockResp.statusCode()).thenReturn(201);
                when(mockResp.statusMessage()).thenReturn("Created");
                when(mockResp.headers()).thenReturn(mm);
                when(mockResp.body()).thenReturn(Buffer.buffer("body-content"));

                ProxyService.ProxyResponse res = s.toProxyResponse(mockResp);
                assertEquals(201, res.statusCode());
                assertEquals("Created", res.statusMessage());
                assertEquals("Value1", res.headers().get("X-Test"));
                assertEquals("body-content", res.bodyAsString());
        }

        @Test
        void testToProxyResponse_NullBody() {
                ProxyService s = new ProxyService();
                @SuppressWarnings("unchecked")
                HttpResponse<Buffer> mockResp = mock(HttpResponse.class);
                when(mockResp.statusCode()).thenReturn(204);
                when(mockResp.statusMessage()).thenReturn("No Content");
                when(mockResp.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
                when(mockResp.body()).thenReturn(null);

                ProxyService.ProxyResponse res = s.toProxyResponse(mockResp);
                assertEquals(204, res.statusCode());
                assertEquals("", res.bodyAsString());

                // Assert that the body was converted to an empty Buffer implicitly, killing the
                // test
                // that removes the null ternary check which would lead to an explicit null
                // return.
                assertNotNull(res.body());
        }

        // ── proxy() integration – send branch (GET with null body) ──

        @Test
        @SuppressWarnings("unchecked")
        void testProxy_SendBranch() {
                ProxyService s = new ProxyService();
                s.requestCounter = mock(io.micrometer.core.instrument.Counter.class);
                s.errorCounter = mock(io.micrometer.core.instrument.Counter.class);
                s.requestTimer = mock(io.micrometer.core.instrument.Timer.class);
                s.webClient = mock(WebClient.class);
                s.config = mock(SidecarConfig.class);

                SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
                when(s.config.proxy()).thenReturn(pc);
                when(pc.propagateHeaders()).thenReturn(List.of("X-Prop"));
                // return non-null map to ensure loop executes
                when(pc.addHeaders()).thenReturn(Map.of("X-Added", "constant"));

                SidecarConfig.ProxyConfig.TimeoutConfig tc = mock(SidecarConfig.ProxyConfig.TimeoutConfig.class);
                when(pc.timeout()).thenReturn(tc);
                when(tc.read()).thenReturn(30000);

                SidecarConfig.ProxyConfig.TargetConfig tgt = mock(SidecarConfig.ProxyConfig.TargetConfig.class);
                when(pc.target()).thenReturn(tgt);
                when(tgt.scheme()).thenReturn("http");
                when(tgt.host()).thenReturn("localhost");
                when(tgt.port()).thenReturn(8081);

                HttpRequest<Buffer> req = mock(HttpRequest.class);
                when(s.webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(req);
                when(req.timeout(anyLong())).thenReturn(req);
                when(req.putHeader(anyString(), anyString())).thenReturn(req);
                when(req.addQueryParam(anyString(), anyString())).thenReturn(req);

                HttpResponse<Buffer> mockResp = mock(HttpResponse.class);
                when(mockResp.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
                when(mockResp.statusCode()).thenReturn(200);
                when(mockResp.statusMessage()).thenReturn("OK");
                when(mockResp.body()).thenReturn(Buffer.buffer("ok"));
                when(req.send()).thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockResp));

                // GET with null body → send() branch
                AuthContext ctx = AuthContext.builder().userId("user1").build();
                s.proxy("GET", "/test", Map.of("x-prop", "v"), Map.of("q", "1"), null, ctx)
                                .subscribe().with(item -> {
                                });

                verify(req, times(1)).send();
                verify(req, times(1)).putHeader("X-Prop", "v");
                verify(req, times(1)).putHeader("X-Added", "constant");
                verify(s.requestCounter, atLeastOnce()).increment();
        }

        @Test
        @SuppressWarnings("unchecked")
        void testProxy_EmptyQueryParams_MutantKiller() {
                ProxyService s = new ProxyService();
                s.requestCounter = mock(io.micrometer.core.instrument.Counter.class);
                s.errorCounter = mock(io.micrometer.core.instrument.Counter.class);
                s.requestTimer = mock(io.micrometer.core.instrument.Timer.class);
                s.webClient = mock(WebClient.class);
                s.config = mock(SidecarConfig.class);

                SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
                when(s.config.proxy()).thenReturn(pc);
                when(pc.propagateHeaders()).thenReturn(List.of());
                when(pc.addHeaders()).thenReturn(Map.of());

                SidecarConfig.ProxyConfig.TimeoutConfig tc = mock(SidecarConfig.ProxyConfig.TimeoutConfig.class);
                when(pc.timeout()).thenReturn(tc);
                when(tc.read()).thenReturn(30000);

                SidecarConfig.ProxyConfig.TargetConfig tgt = mock(SidecarConfig.ProxyConfig.TargetConfig.class);
                when(pc.target()).thenReturn(tgt);
                when(tgt.scheme()).thenReturn("http");
                when(tgt.host()).thenReturn("localhost");
                when(tgt.port()).thenReturn(8081);

                HttpRequest<Buffer> req = mock(HttpRequest.class);
                when(s.webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(req);
                when(req.timeout(anyLong())).thenReturn(req);

                HttpResponse<Buffer> mockResp = mock(HttpResponse.class);
                when(mockResp.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
                when(mockResp.statusCode()).thenReturn(200);
                when(mockResp.statusMessage()).thenReturn("OK");
                when(mockResp.body()).thenReturn(Buffer.buffer("ok"));
                when(req.send()).thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockResp));

                // Create a malicious map mock to kill the !isEmpty() conditionals.
                // The correct code checks isEmpty() and skips entrySet().
                // The mutant with 'true' replacement ignores isEmpty(), asks for entrySet() and
                // crashes!
                Map<String, String> purePoisonQueryParams = mock(Map.class);
                when(purePoisonQueryParams.isEmpty()).thenReturn(true);
                when(purePoisonQueryParams.entrySet())
                                .thenThrow(new RuntimeException("POISON - Equivalent Mutant Killed!"));

                AuthContext ctx = AuthContext.anonymous();
                s.proxy("GET", "/test", Map.of(), purePoisonQueryParams, null, ctx)
                                .subscribe().with(item -> {
                                });

                verify(req, times(1)).send();
                verify(req, never()).addQueryParam(anyString(), anyString());
        }

        @Test
        @SuppressWarnings("unchecked")
        void testProxy_SendBranch_GetWithBody() {
                ProxyService s = new ProxyService();
                s.requestCounter = mock(io.micrometer.core.instrument.Counter.class);
                s.errorCounter = mock(io.micrometer.core.instrument.Counter.class);
                s.requestTimer = mock(io.micrometer.core.instrument.Timer.class);
                s.webClient = mock(WebClient.class);
                s.config = mock(SidecarConfig.class);

                SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
                when(s.config.proxy()).thenReturn(pc);
                when(pc.propagateHeaders()).thenReturn(List.of());
                when(pc.addHeaders()).thenReturn(Map.of());

                SidecarConfig.ProxyConfig.TimeoutConfig tc = mock(SidecarConfig.ProxyConfig.TimeoutConfig.class);
                when(pc.timeout()).thenReturn(tc);
                when(tc.read()).thenReturn(30000);

                SidecarConfig.ProxyConfig.TargetConfig tgt = mock(SidecarConfig.ProxyConfig.TargetConfig.class);
                when(pc.target()).thenReturn(tgt);
                when(tgt.scheme()).thenReturn("http");
                when(tgt.host()).thenReturn("localhost");
                when(tgt.port()).thenReturn(8081);

                HttpRequest<Buffer> req = mock(HttpRequest.class);
                when(s.webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(req);
                when(req.timeout(anyLong())).thenReturn(req);

                HttpResponse<Buffer> mockResp = mock(HttpResponse.class);
                when(mockResp.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
                when(mockResp.statusCode()).thenReturn(200);
                when(mockResp.statusMessage()).thenReturn("OK");
                when(mockResp.body()).thenReturn(Buffer.buffer("ok"));
                when(req.send()).thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockResp));

                io.vertx.core.http.HttpServerRequest coreReq = mock(io.vertx.core.http.HttpServerRequest.class);

                // test GET with a body. it should still map to send()
                AuthContext ctx = AuthContext.anonymous();
                s.proxy("GET", "/test", Map.of(), Map.of(), coreReq, ctx)
                                .subscribe().with(item -> {
                                });

                verify(req, times(1)).send();
                verify(req, never()).sendStream(any(io.vertx.mutiny.core.streams.ReadStream.class));
        }

        // ── proxy() integration – sendStream branch (POST with body) ──

        @Test
        @SuppressWarnings("unchecked")
        void testProxy_SendStreamBranch() {
                ProxyService s = new ProxyService();
                s.requestCounter = mock(io.micrometer.core.instrument.Counter.class);
                s.errorCounter = mock(io.micrometer.core.instrument.Counter.class);
                s.requestTimer = mock(io.micrometer.core.instrument.Timer.class);
                s.webClient = mock(WebClient.class);
                s.config = mock(SidecarConfig.class);

                SidecarConfig.ProxyConfig pc = mock(SidecarConfig.ProxyConfig.class);
                when(s.config.proxy()).thenReturn(pc);
                when(pc.propagateHeaders()).thenReturn(List.of());
                when(pc.addHeaders()).thenReturn(Map.of());
                SidecarConfig.ProxyConfig.TimeoutConfig tc = mock(SidecarConfig.ProxyConfig.TimeoutConfig.class);
                when(pc.timeout()).thenReturn(tc);
                when(tc.read()).thenReturn(1000);
                SidecarConfig.ProxyConfig.TargetConfig tgt = mock(SidecarConfig.ProxyConfig.TargetConfig.class);
                when(pc.target()).thenReturn(tgt);
                when(tgt.scheme()).thenReturn("http");
                when(tgt.host()).thenReturn("host");
                when(tgt.port()).thenReturn(80);

                HttpRequest<Buffer> req = mock(HttpRequest.class);
                when(s.webClient.request(any(), anyInt(), anyString(), anyString())).thenReturn(req);
                when(req.timeout(anyLong())).thenReturn(req);
                when(req.putHeader(anyString(), anyString())).thenReturn(req);

                HttpResponse<Buffer> mockResp = mock(HttpResponse.class);
                when(mockResp.headers()).thenReturn(io.vertx.mutiny.core.MultiMap.caseInsensitiveMultiMap());
                when(mockResp.statusCode()).thenReturn(200);
                when(mockResp.statusMessage()).thenReturn("OK");
                when(mockResp.body()).thenReturn(Buffer.buffer("ok"));

                // sendStream accepts ReadStream<Buffer> – use exact class cast to avoid
                // ambiguity
                when(req.sendStream(any(io.vertx.mutiny.core.streams.ReadStream.class)))
                                .thenReturn(io.smallrye.mutiny.Uni.createFrom().item(mockResp));

                io.vertx.core.http.HttpServerRequest coreReq = mock(io.vertx.core.http.HttpServerRequest.class);

                // POST test
                s.proxy("POST", "/upload", Map.of(), Map.of(), coreReq, AuthContext.anonymous())
                                .subscribe().with(item -> {
                                });
                verify(req, times(1)).sendStream(any(io.vertx.mutiny.core.streams.ReadStream.class));
                verify(req, never()).send();

                // PUT test
                clearInvocations(req);
                s.proxy("PUT", "/upload", Map.of(), Map.of(), coreReq, AuthContext.anonymous())
                                .subscribe().with(item -> {
                                });
                verify(req, times(1)).sendStream(any(io.vertx.mutiny.core.streams.ReadStream.class));
                verify(req, never()).send();

                // PATCH test
                clearInvocations(req);
                s.proxy("PATCH", "/upload", Map.of(), Map.of(), coreReq, AuthContext.anonymous())
                                .subscribe().with(item -> {
                                });
                verify(req, times(1)).sendStream(any(io.vertx.mutiny.core.streams.ReadStream.class));
                verify(req, never()).send();
        }
}
