package space.maatini.sidecar.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.mutiny.core.MultiMap;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Service for proxying authorized requests to the backend container.
 * Handles request forwarding, header propagation, and metrics collection.
 */
@ApplicationScoped
public class ProxyService {

    private static final Logger LOG = Logger.getLogger(ProxyService.class);

    @Inject
    SidecarConfig config;

    @Inject
    Vertx vertx;

    @Inject
    MeterRegistry meterRegistry;

    private WebClient webClient;
    private Counter requestCounter;
    private Counter errorCounter;
    private Timer requestTimer;

    @PostConstruct
    void init() {
        this.webClient = WebClient.create(vertx);
        
        // Initialize metrics
        this.requestCounter = Counter.builder("sidecar.proxy.requests")
            .description("Total number of proxied requests")
            .register(meterRegistry);
        
        this.errorCounter = Counter.builder("sidecar.proxy.errors")
            .description("Total number of proxy errors")
            .register(meterRegistry);
        
        this.requestTimer = Timer.builder("sidecar.proxy.duration")
            .description("Proxy request duration")
            .register(meterRegistry);
    }

    /**
     * Proxies a request to the backend service.
     *
     * @param method The HTTP method
     * @param path The request path
     * @param headers The request headers
     * @param queryParams The query parameters
     * @param body The request body (may be null)
     * @param authContext The authentication context
     * @return A Uni containing the proxy response
     */
    public Uni<ProxyResponse> proxy(
            String method,
            String path,
            Map<String, String> headers,
            Map<String, String> queryParams,
            Buffer body,
            AuthContext authContext) {

        long startTime = System.nanoTime();
        requestCounter.increment();

        String targetHost = config.proxy().target().host();
        int targetPort = config.proxy().target().port();
        String targetScheme = config.proxy().target().scheme();

        String targetUrl = String.format("%s://%s:%d%s", targetScheme, targetHost, targetPort, path);
        LOG.debugf("Proxying %s request to: %s", method, targetUrl);

        HttpMethod httpMethod = HttpMethod.valueOf(method.toUpperCase());
        HttpRequest<Buffer> request = webClient
            .request(httpMethod, targetPort, targetHost, path)
            .timeout(config.proxy().timeout().read());

        // Add query parameters
        if (queryParams != null && !queryParams.isEmpty()) {
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                request.addQueryParam(entry.getKey(), entry.getValue());
            }
        }

        // Propagate configured headers
        propagateHeaders(request, headers);

        // Add authentication context headers
        addAuthContextHeaders(request, authContext);

        // Send request with or without body
        Uni<HttpResponse<Buffer>> responseUni;
        if (body != null && body.length() > 0) {
            responseUni = request.sendBuffer(body);
        } else {
            responseUni = request.send();
        }

        return responseUni
            .onItem().transform(response -> {
                long duration = System.nanoTime() - startTime;
                requestTimer.record(duration, TimeUnit.NANOSECONDS);
                
                LOG.debugf("Proxy response: status=%d, duration=%dms", 
                    response.statusCode(), TimeUnit.NANOSECONDS.toMillis(duration));
                
                return toProxyResponse(response);
            })
            .onFailure().invoke(error -> {
                errorCounter.increment();
                LOG.errorf("Proxy request failed: %s", error.getMessage());
            })
            .onFailure().recoverWithItem(error -> 
                ProxyResponse.error(502, "Bad Gateway: " + error.getMessage())
            );
    }

    /**
     * Propagates configured headers from the original request.
     */
    private void propagateHeaders(HttpRequest<Buffer> request, Map<String, String> headers) {
        if (headers == null) {
            return;
        }

        List<String> propagateList = config.proxy().propagateHeaders();
        for (String headerName : propagateList) {
            String value = headers.get(headerName);
            if (value == null) {
                // Try case-insensitive lookup
                value = headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(headerName))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
            }
            if (value != null) {
                request.putHeader(headerName, value);
            }
        }

        // Also propagate Content-Type if present
        String contentType = headers.get("Content-Type");
        if (contentType == null) {
            contentType = headers.get("content-type");
        }
        if (contentType != null) {
            request.putHeader("Content-Type", contentType);
        }

        // Propagate Accept header
        String accept = headers.get("Accept");
        if (accept == null) {
            accept = headers.get("accept");
        }
        if (accept != null) {
            request.putHeader("Accept", accept);
        }
    }

    /**
     * Adds authentication context information as headers.
     */
    private void addAuthContextHeaders(HttpRequest<Buffer> request, AuthContext authContext) {
        if (authContext == null || !authContext.isAuthenticated()) {
            return;
        }

        Map<String, String> addHeaders = config.proxy().addHeaders();
        if (addHeaders == null || addHeaders.isEmpty()) {
            // Use default headers
            request.putHeader("X-Auth-User-Id", authContext.userId());
            if (authContext.email() != null) {
                request.putHeader("X-Auth-User-Email", authContext.email());
            }
            if (authContext.roles() != null && !authContext.roles().isEmpty()) {
                request.putHeader("X-Auth-User-Roles", String.join(",", authContext.roles()));
            }
            if (authContext.tenant() != null) {
                request.putHeader("X-Auth-Tenant", authContext.tenant());
            }
            return;
        }

        // Process configured headers with placeholders
        for (Map.Entry<String, String> entry : addHeaders.entrySet()) {
            String headerName = entry.getKey();
            String headerValue = resolvePlaceholders(entry.getValue(), authContext);
            if (headerValue != null && !headerValue.isEmpty()) {
                request.putHeader(headerName, headerValue);
            }
        }
    }

    /**
     * Resolves placeholders in header values.
     * Supports: ${user.id}, ${user.email}, ${user.roles}, ${user.tenant}
     */
    private String resolvePlaceholders(String template, AuthContext authContext) {
        if (template == null) {
            return null;
        }

        String result = template;
        result = result.replace("${user.id}", authContext.userId() != null ? authContext.userId() : "");
        result = result.replace("${user.email}", authContext.email() != null ? authContext.email() : "");
        result = result.replace("${user.roles}", authContext.roles() != null ? String.join(",", authContext.roles()) : "");
        result = result.replace("${user.tenant}", authContext.tenant() != null ? authContext.tenant() : "");
        result = result.replace("${user.name}", authContext.name() != null ? authContext.name() : "");

        return result;
    }

    /**
     * Converts a Vert.x HTTP response to a ProxyResponse.
     */
    private ProxyResponse toProxyResponse(HttpResponse<Buffer> response) {
        Map<String, String> responseHeaders = new java.util.HashMap<>();
        MultiMap headers = response.headers();
        for (String name : headers.names()) {
            responseHeaders.put(name, headers.get(name));
        }

        Buffer body = response.body();
        return new ProxyResponse(
            response.statusCode(),
            response.statusMessage(),
            responseHeaders,
            body != null ? body : Buffer.buffer()
        );
    }

    /**
     * Response from the proxy operation.
     */
    public record ProxyResponse(
        int statusCode,
        String statusMessage,
        Map<String, String> headers,
        Buffer body
    ) {
        /**
         * Creates an error response.
         */
        public static ProxyResponse error(int statusCode, String message) {
            return new ProxyResponse(
                statusCode,
                message,
                Map.of("Content-Type", "application/json"),
                Buffer.buffer("{\"error\":\"" + message + "\"}")
            );
        }

        /**
         * Returns true if this is a successful response (2xx status).
         */
        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        /**
         * Returns the body as a string.
         */
        public String bodyAsString() {
            return body != null ? body.toString() : "";
        }
    }
}
