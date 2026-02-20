package space.maatini.sidecar.resource;

import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.mutiny.core.buffer.Buffer;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.jboss.logging.Logger;

import space.maatini.sidecar.service.ProxyService;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin catch-all JAX-RS resource that proxies requests to the backend.
 * Authentication and authorization are handled entirely by
 * {@link space.maatini.sidecar.filter.AuthProxyFilter}.
 * This resource only handles the actual proxy forwarding.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class ProxyResource {

    private static final Logger LOG = Logger.getLogger(ProxyResource.class);

    @Inject
    ProxyService proxyService;

    @Context
    HttpHeaders headers;

    @Context
    UriInfo uriInfo;

    @Context
    HttpServerRequest request;

    @Path("api/{path:.*}")
    @GET
    public Uni<Response> proxyGet(@PathParam("path") String path) {
        return executeProxy("GET", "/api/" + path, null);
    }

    @Path("api/{path:.*}")
    @POST
    @Consumes(MediaType.WILDCARD)
    public Uni<Response> proxyPost(@PathParam("path") String path, InputStream body) {
        return executeProxy("POST", "/api/" + path, readBody(body));
    }

    @Path("api/{path:.*}")
    @PUT
    @Consumes(MediaType.WILDCARD)
    public Uni<Response> proxyPut(@PathParam("path") String path, InputStream body) {
        return executeProxy("PUT", "/api/" + path, readBody(body));
    }

    @Path("api/{path:.*}")
    @PATCH
    @Consumes(MediaType.WILDCARD)
    public Uni<Response> proxyPatch(@PathParam("path") String path, InputStream body) {
        return executeProxy("PATCH", "/api/" + path, readBody(body));
    }

    @Path("api/{path:.*}")
    @DELETE
    public Uni<Response> proxyDelete(@PathParam("path") String path) {
        return executeProxy("DELETE", "/api/" + path, null);
    }

    @Path("api/{path:.*}")
    @OPTIONS
    public Uni<Response> proxyOptions(@PathParam("path") String path) {
        return executeProxy("OPTIONS", "/api/" + path, null);
    }

    @Path("api/{path:.*}")
    @HEAD
    public Uni<Response> proxyHead(@PathParam("path") String path) {
        return executeProxy("HEAD", "/api/" + path, null);
    }

    /**
     * Executes the proxy request. Auth context is already set by AuthProxyFilter.
     */
    private Uni<Response> executeProxy(String method, String path, Buffer body) {

        Map<String, String> headerMap = extractHeaders();
        Map<String, String> queryParams = extractQueryParams();

        return proxyService.proxy(method, path, headerMap, queryParams, body, null)
                .map(proxyResponse -> {
                    Response.ResponseBuilder responseBuilder = Response.status(proxyResponse.statusCode());

                    for (Map.Entry<String, String> header : proxyResponse.headers().entrySet()) {
                        String headerName = header.getKey();
                        if (!headerName.equalsIgnoreCase("Transfer-Encoding") &&
                                !headerName.equalsIgnoreCase("Content-Length")) {
                            responseBuilder.header(headerName, header.getValue());
                        }
                    }

                    if (proxyResponse.body() != null && proxyResponse.body().length() > 0) {
                        responseBuilder.entity(proxyResponse.body().getBytes());
                    }

                    return responseBuilder.build();
                })
                .onFailure().recoverWithItem(error -> {
                    LOG.errorf(error, "Proxy error for %s %s", method, path);
                    return Response
                            .status(Response.Status.INTERNAL_SERVER_ERROR)
                            .entity(Map.of("error", "Internal server error"))
                            .build();
                });
    }

    private Map<String, String> extractHeaders() {
        Map<String, String> headerMap = new HashMap<>();
        for (String headerName : headers.getRequestHeaders().keySet()) {
            headerMap.put(headerName, headers.getHeaderString(headerName));
        }
        return headerMap;
    }

    private Map<String, String> extractQueryParams() {
        Map<String, String> params = new HashMap<>();
        uriInfo.getQueryParameters().forEach((key, values) -> {
            if (!values.isEmpty()) {
                params.put(key, values.get(0));
            }
        });
        return params;
    }

    private Buffer readBody(InputStream inputStream) {
        if (inputStream == null) {
            return null;
        }
        try {
            byte[] bytes = inputStream.readAllBytes();
            return bytes.length > 0 ? Buffer.buffer(bytes) : null;
        } catch (Exception e) {
            LOG.warnf("Failed to read request body: %s", e.getMessage());
            return null;
        }
    }
}
