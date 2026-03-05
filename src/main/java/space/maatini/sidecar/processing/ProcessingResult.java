package space.maatini.sidecar.processing;

import jakarta.ws.rs.core.Response;
import space.maatini.sidecar.common.model.AuthContext;
import space.maatini.sidecar.common.model.PolicyDecision;
import space.maatini.sidecar.web.filter.AuthProxyFilter.ErrorResponse;

/**
 * Sealed Interface für alle Pipeline-Ergebnisse – jetzt mit korrektem 401.
 */
public sealed interface ProcessingResult {

    record Skip() implements ProcessingResult {
    }

    record Proceed(AuthContext authContext) implements ProcessingResult {
    }

    record Forbidden(PolicyDecision decision) implements ProcessingResult {
    }

    record Unauthorized(String message) implements ProcessingResult {
    } // NEU

    record Error(String message) implements ProcessingResult {
    }

    static Skip skip() {
        return new Skip();
    }

    static Proceed proceed(AuthContext ctx) {
        return new Proceed(ctx);
    }

    static Forbidden forbidden(PolicyDecision d) {
        return new Forbidden(d);
    }

    static Unauthorized unauthorized(String message) {
        return new Unauthorized(message);
    }

    static Error error(String message) {
        return new Error(message);
    }

    default boolean isProceed() {
        return this instanceof Proceed;
    }

    default Response toErrorResponse() {
        return switch (this) {
            case Forbidden f -> Response.status(Response.Status.FORBIDDEN)
                    .entity(new ErrorResponse("forbidden", f.decision().reason()))
                    .build();
            case Unauthorized u -> Response.status(Response.Status.UNAUTHORIZED)
                    .header("WWW-Authenticate", "Bearer")
                    .entity(new ErrorResponse("unauthorized", u.message()))
                    .build();
            case Error e -> Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(new ErrorResponse("error", e.message()))
                    .build();
            default -> Response.status(500).build();
        };
    }
}
