package space.maatini.sidecar.infrastructure.util;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

class RequestUtilsPojoTest {

    @Test
    void testExtractHeadersFromHttpServerRequest() {
        HttpServerRequest request = Mockito.mock(HttpServerRequest.class);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Authorization", "Bearer token123");
        headers.add("X-Custom-Header", "value");
        
        when(request.headers()).thenReturn(headers);

        Map<String, String> extracted = RequestUtils.extractHeaders(request);

        // Verify content — Vert.x MultiMap internally lowercases keys
        assertEquals("Bearer token123", extracted.get("authorization"));
        assertEquals("value", extracted.get("x-custom-header"));
        assertNotNull(extracted);
    }

    @Test
    void testExtractQueryParams() {
        io.vertx.ext.web.RoutingContext ctx = Mockito.mock(io.vertx.ext.web.RoutingContext.class);
        MultiMap params = MultiMap.caseInsensitiveMultiMap();
        params.add("UserId", "123");
        
        when(ctx.queryParams()).thenReturn(params);

        Map<String, String> extracted = RequestUtils.extractQueryParams(ctx);

        // Vert.x MultiMap lowercases keys
        assertEquals("123", extracted.get("userid"));
    }
}
