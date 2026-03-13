package space.maatini.sidecar.infrastructure.util;
 
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
 
import java.util.Map;
import java.util.TreeMap;
 
/**
 * Utility class for common request processing tasks.
 */
public final class RequestUtils {
 
    private RequestUtils() {
        // Utility class
    }
 
    /**
     * Extracts all request headers as a single-valued map from
     * ContainerRequestContext.
     */
    public static Map<String, String> extractHeaders(ContainerRequestContext requestContext) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (requestContext.getHeaders() != null) {
            for (String headerName : requestContext.getHeaders().keySet()) {
                headers.put(headerName, requestContext.getHeaderString(headerName));
            }
        }
        return headers;
    }
 
    /**
     * Extracts all request headers from JAX-RS HttpHeaders.
     */
    public static Map<String, String> extractHeaders(HttpHeaders httpHeaders) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (httpHeaders != null && httpHeaders.getRequestHeaders() != null) {
            for (String headerName : httpHeaders.getRequestHeaders().keySet()) {
                headers.put(headerName, httpHeaders.getHeaderString(headerName));
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
     */
    public static Map<String, String> extractHeaders(HttpServerRequest request) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        request.headers().forEach(entry -> headers.put(entry.getKey(), entry.getValue()));
        return headers;
    }

    /**
     * Extracts query parameters from Vert.x RoutingContext.
     */
    public static Map<String, String> extractQueryParams(RoutingContext ctx) {
        Map<String, String> params = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ctx.queryParams().forEach(entry -> params.put(entry.getKey(), entry.getValue()));
        return params;
    }

    /**
     * Extracts query parameters as a single-valued map from UriInfo.
     */
    public static Map<String, String> extractQueryParams(UriInfo uriInfo) {
        Map<String, String> params = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
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
