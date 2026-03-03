package space.maatini.sidecar.filter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RateLimitFilterPojoTest {

    private RateLimitFilter filter;
    private SidecarConfig config;
    private ContainerRequestContext requestContext;
    private HttpServerRequest httpRequest;
    private UriInfo uriInfo;

    private SidecarConfig.RateLimitConfig rateLimitConfig;

    @BeforeEach
    void setup() throws Exception {
        filter = new RateLimitFilter();

        config = mock(SidecarConfig.class);
        rateLimitConfig = mock(SidecarConfig.RateLimitConfig.class);
        when(config.rateLimit()).thenReturn(rateLimitConfig);
        when(rateLimitConfig.enabled()).thenReturn(true);
        when(rateLimitConfig.requestsPerSecond()).thenReturn(1);
        when(rateLimitConfig.burstSize()).thenReturn(1);

        httpRequest = mock(HttpServerRequest.class);
        MeterRegistry registry = mock(MeterRegistry.class);
        Counter mockCounter = mock(Counter.class);

        setField(filter, "config", config);
        setField(filter, "httpRequest", httpRequest);
        setField(filter, "meterRegistry", registry);

        filter.init(); // Initialize the buckets and counters

        setField(filter, "rateLimitExceededCounter", mockCounter);

        requestContext = mock(ContainerRequestContext.class);
        uriInfo = mock(UriInfo.class);
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getPath()).thenReturn("/api/test");

        SocketAddress socketAddress = mock(SocketAddress.class);
        when(socketAddress.host()).thenReturn("192.168.1.10");
        when(httpRequest.remoteAddress()).thenReturn(socketAddress);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testFilter_Disabled() throws Exception {
        when(rateLimitConfig.enabled()).thenReturn(false);
        filter.filter(requestContext);
        verify(requestContext, never()).getProperty(anyString());
    }

    @Test
    void testFilter_InternalHealth() throws Exception {
        when(uriInfo.getPath()).thenReturn("/q/health");
        filter.filter(requestContext);
        verify(requestContext, never()).getProperty(anyString());
    }

    @Test
    void testFilter_RateLimitExceeded_ByIp_TrustedProxyXForwardedFor() throws Exception {
        when(rateLimitConfig.trustedProxies()).thenReturn(List.of("192.168.1.10"));
        when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("10.0.0.5, 10.0.0.12");

        // First request should pass
        filter.filter(requestContext);
        verify(requestContext, never()).abortWith(any(Response.class));

        // Second request should be blocked
        filter.filter(requestContext);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        Response response = captor.getValue();
        assertEquals(429, response.getStatus());
        assertNotNull(response.getHeaderString("Retry-After"));
    }

    @Test
    void testFilter_RateLimitExceeded_ByIp_TrustedProxyXRealIp() throws Exception {
        when(rateLimitConfig.trustedProxies()).thenReturn(List.of("192.168.1.10"));
        when(requestContext.getHeaderString("X-Real-IP")).thenReturn("10.0.0.6");

        filter.filter(requestContext);
        filter.filter(requestContext);

        verify(requestContext).abortWith(any(Response.class));
    }

    @Test
    void testFilter_RateLimitExceeded_ByIp_UntrustedProxy() throws Exception {
        when(rateLimitConfig.trustedProxies()).thenReturn(List.of("192.168.1.11")); // different IP
        when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("10.0.0.5");

        filter.filter(requestContext);
        filter.filter(requestContext);

        // It will use 192.168.1.10 as IP, ignore X-Forwarded-For because it's untrusted
        verify(requestContext).abortWith(any(Response.class));
    }

    @Test
    void testFilter_RateLimitExceeded_ByUserId() throws Exception {
        AuthContext authContext = AuthContext.builder().userId("user123").build();
        when(requestContext.getProperty("auth.context")).thenReturn(authContext);

        filter.filter(requestContext);
        filter.filter(requestContext);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());

        Response response = captor.getValue();
        assertEquals(429, response.getStatus());
        assertNotNull(response.getHeaderString("Retry-After"));
        assertTrue(response.getEntity() instanceof AuthProxyFilter.ErrorResponse);
        AuthProxyFilter.ErrorResponse error = (AuthProxyFilter.ErrorResponse) response.getEntity();
        assertEquals("too_many_requests", error.code());
        assertEquals("Rate limit exceeded. Try again later.", error.message());
    }

    @Test
    void testFilter_RateLimitExceeded_ByIp_UnauthenticatedUser() throws Exception {
        AuthContext authContext = AuthContext.anonymous();
        when(requestContext.getProperty("auth.context")).thenReturn(authContext);

        when(rateLimitConfig.trustedProxies()).thenReturn(List.of("192.168.1.10"));
        when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("10.0.0.8");

        filter.filter(requestContext);
        filter.filter(requestContext);

        verify(requestContext).abortWith(any(Response.class));
    }

    @Test
    void testFilter_RateLimit_NullHttpRequest() throws Exception {
        setField(filter, "httpRequest", null);
        when(rateLimitConfig.trustedProxies()).thenReturn(List.of("192.168.1.10"));

        filter.filter(requestContext);
        filter.filter(requestContext);

        verify(requestContext).abortWith(any(Response.class));
    }

    @Test
    void testFilter_RateLimit_NullRemoteAddress() throws Exception {
        when(httpRequest.remoteAddress()).thenReturn(null);
        when(rateLimitConfig.trustedProxies()).thenReturn(null);

        filter.filter(requestContext);
        filter.filter(requestContext);

        verify(requestContext).abortWith(any(Response.class));
    }

    @Test
    void testFilter_RateLimit_EmptyXForwardedFor() throws Exception {
        when(rateLimitConfig.trustedProxies()).thenReturn(List.of("192.168.1.10"));
        when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("");
        when(requestContext.getHeaderString("X-Real-IP")).thenReturn("10.0.0.6");

        filter.filter(requestContext);
        filter.filter(requestContext);

        verify(requestContext).abortWith(any(Response.class));
    }

    @Test
    void testFilter_RateLimit_EmptyXRealIp() throws Exception {
        when(rateLimitConfig.trustedProxies()).thenReturn(List.of("192.168.1.10"));
        when(requestContext.getHeaderString("X-Forwarded-For")).thenReturn("");
        when(requestContext.getHeaderString("X-Real-IP")).thenReturn("");

        filter.filter(requestContext);
        filter.filter(requestContext);

        verify(requestContext).abortWith(any(Response.class));
    }

    @Test
    void testFilter_RateLimitExceeded_CounterIncremented() throws Exception {
        Counter mockCounter = mock(Counter.class);
        setField(filter, "rateLimitExceededCounter", mockCounter);

        when(rateLimitConfig.trustedProxies()).thenReturn(List.of("192.168.1.11")); // untrusted
        filter.filter(requestContext);
        filter.filter(requestContext);

        verify(mockCounter, times(1)).increment();
    }
}
