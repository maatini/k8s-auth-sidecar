package space.maatini.sidecar.infrastructure.util;
 
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
 
import java.util.HashMap;
import java.util.Map;
 
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
        Map<String, String> headers = new HashMap<>();
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
        Map<String, String> headers = new HashMap<>();
        if (httpHeaders != null && httpHeaders.getRequestHeaders() != null) {
            for (String headerName : httpHeaders.getRequestHeaders().keySet()) {
                headers.put(headerName, httpHeaders.getHeaderString(headerName));
            }
        }
        return headers;
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
