package space.maatini.sidecar.web.filter;

import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;
import space.maatini.sidecar.processing.SidecarRequestProcessor;
import space.maatini.sidecar.processing.ProcessingResult;

import java.util.List;

/**
 * Entry point for all incoming requests.
 * Orchestration is handled by SidecarRequestProcessor.
 */
public class AuthProxyFilter {

    @Inject
    SidecarRequestProcessor processor;

    @ServerRequestFilter(preMatching = true)
    public Uni<Response> filter(ContainerRequestContext ctx) {
        return processor.process(ctx)
                .map(result -> (result instanceof ProcessingResult.Proceed || result instanceof ProcessingResult.Skip)
                        ? null
                        : result.toErrorResponse());
    }

    /**
     * DTO for error responses. Used by ProcessingResult to build responses.
     */
    public record ErrorResponse(String status, String message, List<String> details) {
        public ErrorResponse(String status, String message) {
            this(status, message, List.of());
        }
    }
}
