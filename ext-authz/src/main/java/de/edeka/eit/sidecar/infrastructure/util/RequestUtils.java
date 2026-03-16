package de.edeka.eit.sidecar.infrastructure.util;
 
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
 
import java.util.HashMap;
import java.util.Map;
 
/**
 * Utility class for common request processing tasks.
 * Uses HashMap instead of TreeMap for reduced allocation cost.
 * Case-insensitivity is handled at the source level (Vert.x MultiMap)
 * or by lowercasing keys for JAX-RS sources.
 */
public final class RequestUtils {
 
    private RequestUtils() {
        // Utility class
    }
 
    /**
     * Extracts all request headers as a single-valued map from
     * ContainerRequestContext. Keys are lowercased for case-insensitive lookup.
     */
    public static Map<String, String> extractHeaders(ContainerRequestContext requestContext) {
        Map<String, String> headers = new HashMap<>();
        if (requestContext.getHeaders() != null) {
            for (String headerName : requestContext.getHeaders().keySet()) {
                headers.put(headerName.toLowerCase(), requestContext.getHeaderString(headerName));
            }
        }
        return headers;
    }
 
    /**
     * Extracts all request headers from JAX-RS HttpHeaders.
     * Keys are lowercased for case-insensitive lookup.
     */
    public static Map<String, String> extractHeaders(HttpHeaders httpHeaders) {
        Map<String, String> headers = new HashMap<>();
        if (httpHeaders != null && httpHeaders.getRequestHeaders() != null) {
            for (String headerName : httpHeaders.getRequestHeaders().keySet()) {
                headers.put(headerName.toLowerCase(), httpHeaders.getHeaderString(headerName));
            }
        }
        return headers;
    }
 
    /**
     * Extracts all request headers from Vert.x RoutingContext.
     */
    public static Map<String, String> extractHeaders(RoutingContext ctx) {
        return extractHeaders(ctx.request());
    }

    /**
     * Extracts all request headers from Vert.x HttpServerRequest.
     * Vert.x MultiMap is already case-insensitive, so we iterate directly
     * into a HashMap without TreeMap overhead.
     */
    public static Map<String, String> extractHeaders(HttpServerRequest request) {
        Map<String, String> headers = new HashMap<>();
        request.headers().forEach(entry -> headers.put(entry.getKey().toLowerCase(), entry.getValue()));
        return headers;
    }

    /**
     * Extracts query parameters from Vert.x RoutingContext.
     */
    public static Map<String, String> extractQueryParams(RoutingContext ctx) {
        Map<String, String> params = new HashMap<>();
        ctx.queryParams().forEach(entry -> params.put(entry.getKey().toLowerCase(), entry.getValue()));
        return params;
    }

    /**
     * Extracts query parameters as a single-valued map from UriInfo.
     */
    public static Map<String, String> extractQueryParams(UriInfo uriInfo) {
        Map<String, String> params = new HashMap<>();
        if (uriInfo != null && uriInfo.getQueryParameters() != null) {
            uriInfo.getQueryParameters().forEach((key, values) -> {
                if (values != null && !values.isEmpty()) {
                    params.put(key, values.get(0));
                }
            });
        }
        return params;
    }
}
