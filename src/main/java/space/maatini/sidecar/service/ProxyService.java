package space.maatini.sidecar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
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

    @Inject
    ObjectMapper objectMapper;

    private WebClient webClient;
    private Counter requestCounter;
    private Counter errorCounter;
    private Timer requestTimer;

    @PostConstruct
    void init() {
        WebClientOptions options = new WebClientOptions()
                .setConnectTimeout(config.proxy().timeout().connect())
                .setIdleTimeout(30)
                .setMaxPoolSize(config.proxy().poolSize())
                .setKeepAlive(true);

        this.webClient = WebClient.create(vertx, options);

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

    @PreDestroy
    void shutdown() {
        if (webClient != null) {
            webClient.close();
        }
    }

    /**
     * Proxies a request to the backend service.
     */
    public Uni<ProxyResponse> proxy(
            String method,
            String path,
            Map<String, String> headers,
            Map<String, String> queryParams,
            io.vertx.core.http.HttpServerRequest clientRequest,
            AuthContext authContext) {

        long startTime = System.nanoTime();
        requestCounter.increment();

        int targetPort = config.proxy().target().port();
        String targetHost = config.proxy().target().host();

        String targetUrl = buildTargetUrl(path);
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
        if (clientRequest != null && (method.equalsIgnoreCase("POST") || method.equalsIgnoreCase("PUT")
                || method.equalsIgnoreCase("PATCH"))) {
            io.vertx.mutiny.core.http.HttpServerRequest mutinyReq = io.vertx.mutiny.core.http.HttpServerRequest
                    .newInstance(clientRequest);
            responseUni = request.sendStream(mutinyReq);
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
                .onFailure().recoverWithItem(error -> {
                    errorCounter.increment();
                    LOG.errorf("Proxy request failed for %s %s: %s", method, path, error.getMessage());
                    return ProxyResponse.error(503, "Service Unavailable: Backend system cannot be reached.");
                });
    }

    /**
     * Propagates configured headers from the original request.
     */
    void propagateHeaders(HttpRequest<Buffer> request, Map<String, String> headers) {
        if (headers == null) {
            return;
        }

        List<String> propagateList = config.proxy().propagateHeaders();
        for (String headerName : propagateList) {
            String value = headers.get(headerName);
            if (value == null) {
                value = headers.entrySet().stream()
                        .filter(e -> e.getKey().equalsIgnoreCase(headerName) && e.getValue() != null)
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
    void addAuthContextHeaders(HttpRequest<Buffer> request, AuthContext authContext) {
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
     * Supports: ${user.id}, ${user.email}, ${user.roles}, ${user.name}
     */
    String resolvePlaceholders(String template, AuthContext authContext) {
        if (template == null) {
            return null;
        }

        String result = template;
        result = result.replace("${user.id}", authContext.userId() != null ? authContext.userId() : "");
        result = result.replace("${user.email}", authContext.email() != null ? authContext.email() : "");
        result = result.replace("${user.roles}",
                authContext.roles() != null ? String.join(",", authContext.roles()) : "");
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
                body != null ? body : Buffer.buffer());
    }

    /**
     * Response from the proxy operation.
     */
    public record ProxyResponse(
            int statusCode,
            String statusMessage,
            Map<String, String> headers,
            Buffer body) {

        public static ProxyResponse error(int statusCode, String message) {
            String sanitizedMessage = message != null ? message.replace("\"", "\\\"") : "Internal error";
            String jsonRaw = "{\"error\":\"" + sanitizedMessage + "\"}";
            return new ProxyResponse(
                    statusCode,
                    message,
                    Map.of("Content-Type", "application/json"),
                    Buffer.buffer(jsonRaw));
        }

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        public String bodyAsString() {
            return body != null ? body.toString() : "";
        }
    }

    /**
     * Extracted to make the URL building easily testable
     */
    String buildTargetUrl(String path) {
        return String.format("%s://%s:%d%s",
                config.proxy().target().scheme(),
                config.proxy().target().host(),
                config.proxy().target().port(),
                path);
    }
}
