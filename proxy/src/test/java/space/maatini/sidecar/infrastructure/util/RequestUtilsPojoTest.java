package space.maatini.sidecar.infrastructure.util;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class RequestUtilsPojoTest {

    @Test
    void testExtractHeadersFromHttpServerRequestIsCaseInsensitive() {
        HttpServerRequest request = Mockito.mock(HttpServerRequest.class);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("Authorization", "Bearer token123");
        headers.add("X-Custom-Header", "value");
        
        when(request.headers()).thenReturn(headers);

        Map<String, String> extracted = RequestUtils.extractHeaders(request);

        // Verify content
        assertEquals("Bearer token123", extracted.get("Authorization"));
        assertEquals("Bearer token123", extracted.get("authorization")); // Case-insensitive lookup
        assertEquals("value", extracted.get("x-custom-header"));
        
        // Verify we used the right map type (TreeMap with case-insensitive order)
        assertTrue(extracted.containsKey("AUTHORIZATION"));
    }

    @Test
    void testExtractQueryParamsIsCaseInsensitive() {
        // Query parameters are usually case-sensitive in the standard, 
        // but our implementation now uses TreeMap(CASE_INSENSITIVE_ORDER) for consistency
        // with how we handle headers and to simplify OPA policies.
        
        io.vertx.ext.web.RoutingContext ctx = Mockito.mock(io.vertx.ext.web.RoutingContext.class);
        MultiMap params = MultiMap.caseInsensitiveMultiMap();
        params.add("UserId", "123");
        
        when(ctx.queryParams()).thenReturn(params);

        Map<String, String> extracted = RequestUtils.extractQueryParams(ctx);

        assertEquals("123", extracted.get("userid"));
        assertEquals("123", extracted.get("USERID"));
    }
}
