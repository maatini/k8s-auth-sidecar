package space.maatini.sidecar.infrastructure.filter;
 
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import space.maatini.sidecar.application.service.SidecarRequestProcessor;
import space.maatini.sidecar.domain.model.ErrorResponse;
import space.maatini.sidecar.domain.model.ProcessingResult;
import space.maatini.sidecar.domain.model.SidecarRequest;
import space.maatini.sidecar.infrastructure.util.RequestUtils;
 
/**
 * Entry point for all incoming requests.
 */
public class AuthProxyFilter {
 
    @Inject
    SidecarRequestProcessor processor;
 
    @ServerRequestFilter(preMatching = true)
    public Uni<Response> filter(ContainerRequestContext ctx) {
        SidecarRequest request = new SidecarRequest(
                ctx.getMethod(),
                ctx.getUriInfo().getPath(),
                RequestUtils.extractHeaders(ctx),
                RequestUtils.extractQueryParams(ctx.getUriInfo())
        );
 
        return processor.process(request)
                .map(res -> mapToResponse(res, ctx));
    }
 
    private Response mapToResponse(ProcessingResult result, ContainerRequestContext ctx) {
        if (result instanceof ProcessingResult.Proceed p) {
            ctx.setProperty("auth.context", p.authContext());
            return null; // Continue to next filter/resource
        } else if (result instanceof ProcessingResult.Skip) {
            return null; // Continue
        } else if (result instanceof ProcessingResult.Forbidden f) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("forbidden", f.decision().reason()))
                    .build();
        } else if (result instanceof ProcessingResult.Unauthorized u) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .header("WWW-Authenticate", "Bearer")
                    .entity(new ErrorResponse("unauthorized", u.message()))
                    .build();
        } else if (result instanceof ProcessingResult.Error e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("error", e.message()))
                    .build();
        }
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR).build();
    }
}
