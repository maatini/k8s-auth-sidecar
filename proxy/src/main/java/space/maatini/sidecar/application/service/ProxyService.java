package space.maatini.sidecar.application.service;

import io.smallrye.mutiny.Uni;
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.domain.model.ProxyResponse;

import java.util.Map;

/**
 * Interface for proxying requests to the backend.
 */
public interface ProxyService {

    /**
     * Proxies a request to the backend container.
     */
    Uni<ProxyResponse> proxy(
            String method,
            String path,
            Map<String, String> headers,
            Map<String, String> queryParams,
            io.vertx.core.http.HttpServerRequest clientRequest,
            io.vertx.core.http.HttpServerResponse clientResponse,
            AuthContext authContext);

    /**
     * Builds the target URL for a given path.
     */
    String buildTargetUrl(String path);
}
