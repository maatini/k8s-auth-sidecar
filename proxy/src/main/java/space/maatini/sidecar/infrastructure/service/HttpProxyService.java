package space.maatini.sidecar.infrastructure.service;

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
import space.maatini.sidecar.application.service.ProxyService;
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.domain.model.ProxyResponse;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Implementation of ProxyService using Vert.x WebClient.
 */
@ApplicationScoped
public class HttpProxyService implements ProxyService {

    private static final Logger LOG = Logger.getLogger(HttpProxyService.class);

    @Inject
    protected SidecarConfig config;

    @Inject
    Vertx vertx;

    @Inject
    MeterRegistry meterRegistry;

    @Inject
    ObjectMapper objectMapper;

    protected io.micrometer.core.instrument.Timer requestTimer;
    protected WebClient webClient;
    protected Counter requestCounter;
    protected Counter errorCounter;

    @PostConstruct
    void init() {
        requestTimer = meterRegistry.timer("sidecar_proxy_latency_seconds");
        requestCounter = meterRegistry.counter("sidecar_proxy_requests_total");
        errorCounter = meterRegistry.counter("sidecar_proxy_errors_total");

        WebClientOptions options = new WebClientOptions()
                .setConnectTimeout(config.proxy().timeout().connect())
                .setIdleTimeout(30)
                .setMaxPoolSize(config.proxy().poolSize())
                .setKeepAlive(true);

        this.webClient = WebClient.create(vertx, options);
    }

    @PreDestroy
    void shutdown() {
        if (webClient != null) {
            webClient.close();
        }
    }

    @Override
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

        HttpMethod httpMethod = HttpMethod.valueOf(method.toUpperCase());
        HttpRequest<Buffer> request = webClient
                .request(httpMethod, targetPort, targetHost, path)
                .timeout(config.proxy().timeout().read());

        if (queryParams != null && !queryParams.isEmpty()) {
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                request.addQueryParam(entry.getKey(), entry.getValue());
            }
        }

        Map<String, String> propagated = resolvePropagatedHeaders(headers);
        propagated.forEach(request::putHeader);

        Map<String, String> authHeaders = resolveAuthContextHeaders(authContext);
        authHeaders.forEach(request::putHeader);

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
                    recordRequestMetrics(startTime, response.statusCode());
                    return toProxyResponse(response);
                })
                .onFailure().recoverWithItem(error -> {
                    errorCounter.increment();
                    LOG.errorf("Proxy request failed for %s %s: %s", method, path, error.getMessage());
                    return ProxyResponse.error(503, "Service Unavailable: Backend system cannot be reached.");
                });
    }

    @Override
    public String buildTargetUrl(String path) {
        String scheme = config.proxy().target().scheme();
        String host = config.proxy().target().host();
        int port = config.proxy().target().port();
        return scheme + "://" + host + ":" + port + path;
    }

    private Map<String, String> resolvePropagatedHeaders(Map<String, String> headers) {
        Map<String, String> resolved = new java.util.HashMap<>();
        if (headers == null) {
            return resolved;
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
                resolved.put(headerName, value);
            }
        }

        String contentType = headers.get("Content-Type");
        if (contentType == null)
            contentType = headers.get("content-type");
        if (contentType != null)
            resolved.put("Content-Type", contentType);

        String accept = headers.get("Accept");
        if (accept == null)
            accept = headers.get("accept");
        if (accept != null)
            resolved.put("Accept", accept);

        return resolved;
    }

    private Map<String, String> resolveAuthContextHeaders(AuthContext authContext) {
        Map<String, String> resolved = new java.util.HashMap<>();
        if (authContext == null || !authContext.isAuthenticated()) {
            return resolved;
        }

        Map<String, String> addHeaders = config.proxy().addHeaders();
        if (addHeaders == null || addHeaders.isEmpty()) {
            resolved.put("X-Auth-User-Id", authContext.userId());
            if (authContext.email() != null) {
                resolved.put("X-Auth-User-Email", authContext.email());
            }
            if (authContext.roles() != null && !authContext.roles().isEmpty()) {
                resolved.put("X-Auth-User-Roles", String.join(",", authContext.roles()));
            }
            return resolved;
        }

        for (Map.Entry<String, String> entry : addHeaders.entrySet()) {
            String headerName = entry.getKey();
            String headerValue = resolvePlaceholders(entry.getValue(), authContext);
            if (headerValue != null && !headerValue.isEmpty()) {
                resolved.put(headerName, headerValue);
            }
        }
        return resolved;
    }

    private String resolvePlaceholders(String template, AuthContext authContext) {
        if (template == null)
            return null;
        String result = template;
        result = result.replace("${user.id}", authContext.userId() != null ? authContext.userId() : "");
        result = result.replace("${user.email}", authContext.email() != null ? authContext.email() : "");
        result = result.replace("${user.roles}",
                authContext.roles() != null ? String.join(",", authContext.roles()) : "");
        result = result.replace("${user.name}", authContext.name() != null ? authContext.name() : "");

        return result;
    }

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

    private void recordRequestMetrics(long startTime, int statusCode) {
        long duration = System.nanoTime() - startTime;
        if (requestTimer != null) {
            requestTimer.record(duration, TimeUnit.NANOSECONDS);
        }
        LOG.debugf("Proxy response: status=%d, duration=%dms",
                statusCode, TimeUnit.NANOSECONDS.toMillis(duration));
    }
}
