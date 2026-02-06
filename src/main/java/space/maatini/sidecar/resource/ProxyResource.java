package space.maatini.sidecar.resource;

import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.mutiny.core.buffer.Buffer;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.jboss.logging.Logger;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.service.AuthenticationService;
import space.maatini.sidecar.service.PolicyService;
import space.maatini.sidecar.service.ProxyService;
import space.maatini.sidecar.service.RolesService;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * REST resource that handles all incoming requests and proxies them to the backend service.
 * This is an alternative approach to the filter-based proxying.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class ProxyResource {

    private static final Logger LOG = Logger.getLogger(ProxyResource.class);

    @Inject
    SidecarConfig config;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    AuthenticationService authenticationService;

    @Inject
    RolesService rolesService;

    @Inject
    PolicyService policyService;

    @Inject
    ProxyService proxyService;

    @Context
    HttpHeaders headers;

    @Context
    UriInfo uriInfo;

    @Context
    HttpServerRequest request;

    /**
     * Catches all API requests and proxies them to the backend.
     */
    @Path("api/{path:.*}")
    @GET
    public Uni<Response> proxyGet(@PathParam("path") String path) {
        return handleProxy("GET", "/api/" + path, null);
    }

    @Path("api/{path:.*}")
    @POST
    @Consumes(MediaType.WILDCARD)
    public Uni<Response> proxyPost(@PathParam("path") String path, InputStream body) {
        return handleProxy("POST", "/api/" + path, readBody(body));
    }

    @Path("api/{path:.*}")
    @PUT
    @Consumes(MediaType.WILDCARD)
    public Uni<Response> proxyPut(@PathParam("path") String path, InputStream body) {
        return handleProxy("PUT", "/api/" + path, readBody(body));
    }

    @Path("api/{path:.*}")
    @PATCH
    @Consumes(MediaType.WILDCARD)
    public Uni<Response> proxyPatch(@PathParam("path") String path, InputStream body) {
        return handleProxy("PATCH", "/api/" + path, readBody(body));
    }

    @Path("api/{path:.*}")
    @DELETE
    public Uni<Response> proxyDelete(@PathParam("path") String path) {
        return handleProxy("DELETE", "/api/" + path, null);
    }

    @Path("api/{path:.*}")
    @OPTIONS
    public Uni<Response> proxyOptions(@PathParam("path") String path) {
        return handleProxy("OPTIONS", "/api/" + path, null);
    }

    @Path("api/{path:.*}")
    @HEAD
    public Uni<Response> proxyHead(@PathParam("path") String path) {
        return handleProxy("HEAD", "/api/" + path, null);
    }

    /**
     * Handles the proxy logic for all HTTP methods.
     */
    private Uni<Response> handleProxy(String method, String path, Buffer body) {
        LOG.debugf("Proxying %s %s", method, path);

        // Extract auth context
        AuthContext authContext = authenticationService.extractAuthContext(securityIdentity);

        if (!authContext.isAuthenticated() && !isPublicPath(path)) {
            return Uni.createFrom().item(Response
                .status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "Authentication required"))
                .build());
        }

        // Enrich with roles
        return rolesService.enrichWithRoles(authContext)
            .flatMap(enrichedContext -> {
                // Check authorization
                if (config.authz().enabled()) {
                    Map<String, String> headerMap = extractHeaders();
                    Map<String, String> queryParams = extractQueryParams();

                    return policyService.evaluate(enrichedContext, method, path, headerMap, queryParams)
                        .flatMap(decision -> {
                            if (!decision.allowed()) {
                                return Uni.createFrom().item(Response
                                    .status(Response.Status.FORBIDDEN)
                                    .entity(Map.of(
                                        "error", "Access denied",
                                        "reason", decision.reason() != null ? decision.reason() : "Policy denied access"
                                    ))
                                    .build());
                            }
                            // Authorized - proceed with proxy
                            return executeProxy(method, path, body, enrichedContext);
                        });
                }
                // Authorization disabled - proceed with proxy
                return executeProxy(method, path, body, enrichedContext);
            })
            .onFailure().recoverWithItem(error -> {
                LOG.errorf(error, "Proxy error");
                return Response
                    .status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Internal server error"))
                    .build();
            });
    }

    /**
     * Executes the proxy request to the backend.
     */
    private Uni<Response> executeProxy(String method, String path, Buffer body, AuthContext authContext) {
        Map<String, String> headerMap = extractHeaders();
        Map<String, String> queryParams = extractQueryParams();

        return proxyService.proxy(method, path, headerMap, queryParams, body, authContext)
            .map(proxyResponse -> {
                Response.ResponseBuilder responseBuilder = Response.status(proxyResponse.statusCode());

                // Copy response headers
                for (Map.Entry<String, String> header : proxyResponse.headers().entrySet()) {
                    String headerName = header.getKey();
                    // Skip some headers that shouldn't be proxied
                    if (!headerName.equalsIgnoreCase("Transfer-Encoding") &&
                        !headerName.equalsIgnoreCase("Content-Length")) {
                        responseBuilder.header(headerName, header.getValue());
                    }
                }

                // Set response body
                if (proxyResponse.body() != null && proxyResponse.body().length() > 0) {
                    responseBuilder.entity(proxyResponse.body().getBytes());
                }

                return responseBuilder.build();
            });
    }

    /**
     * Extracts headers from the request.
     */
    private Map<String, String> extractHeaders() {
        Map<String, String> headerMap = new HashMap<>();
        for (String headerName : headers.getRequestHeaders().keySet()) {
            headerMap.put(headerName, headers.getHeaderString(headerName));
        }
        return headerMap;
    }

    /**
     * Extracts query parameters from the request.
     */
    private Map<String, String> extractQueryParams() {
        Map<String, String> params = new HashMap<>();
        uriInfo.getQueryParameters().forEach((key, values) -> {
            if (!values.isEmpty()) {
                params.put(key, values.get(0));
            }
        });
        return params;
    }

    /**
     * Reads the request body into a Buffer.
     */
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

    /**
     * Checks if a path is public.
     */
    private boolean isPublicPath(String path) {
        var publicPaths = config.auth().publicPaths();
        if (publicPaths == null) {
            return false;
        }
        for (String publicPath : publicPaths) {
            if (path.startsWith(publicPath.replace("/**", "").replace("/*", ""))) {
                return true;
            }
        }
        return false;
    }
}
