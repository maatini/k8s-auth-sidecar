package space.maatini.sidecar.infrastructure.resource;
 
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import org.jboss.logging.Logger;
 
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.application.service.ProxyService;
import space.maatini.sidecar.domain.model.ProxyResponse;
import space.maatini.sidecar.infrastructure.util.RequestUtils;
 
import java.util.Map;
 
/**
 * Catch-all JAX-RS resource for proxying.
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
 
    @Context
    ContainerRequestContext containerRequestContext;
 
    @Path("api/{path:.*}")
    @GET
    public Uni<Response> proxyGet(@PathParam("path") String path) {
        return executeProxy("GET", "/api/" + path, request);
    }
 
    @Path("api/{path:.*}")
    @POST
    @Consumes(MediaType.WILDCARD)
    public Uni<Response> proxyPost(@PathParam("path") String path) {
        return executeProxy("POST", "/api/" + path, request);
    }
 
    @Path("api/{path:.*}")
    @PUT
    @Consumes(MediaType.WILDCARD)
    public Uni<Response> proxyPut(@PathParam("path") String path) {
        return executeProxy("PUT", "/api/" + path, request);
    }
 
    @Path("api/{path:.*}")
    @PATCH
    @Consumes(MediaType.WILDCARD)
    public Uni<Response> proxyPatch(@PathParam("path") String path) {
        return executeProxy("PATCH", "/api/" + path, request);
    }
 
    @Path("api/{path:.*}")
    @DELETE
    public Uni<Response> proxyDelete(@PathParam("path") String path) {
        return executeProxy("DELETE", "/api/" + path, request);
    }
 
    @Path("api/{path:.*}")
    @OPTIONS
    public Uni<Response> proxyOptions(@PathParam("path") String path) {
        return executeProxy("OPTIONS", "/api/" + path, request);
    }
 
    @Path("api/{path:.*}")
    @HEAD
    public Uni<Response> proxyHead(@PathParam("path") String path) {
        return executeProxy("HEAD", "/api/" + path, request);
    }
 
    private Uni<Response> executeProxy(String method, String path, HttpServerRequest clientRequest) {
        Map<String, String> headerMap = RequestUtils.extractHeaders(headers);
        Map<String, String> queryParams = RequestUtils.extractQueryParams(uriInfo);
 
        AuthContext authContext = (AuthContext) containerRequestContext.getProperty("auth.context");
 
        return proxyService.proxy(method, path, headerMap, queryParams, clientRequest, authContext)
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
}
