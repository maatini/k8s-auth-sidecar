package space.maatini.sidecar.domain.model;
 
import io.quarkus.runtime.annotations.RegisterForReflection;
import space.maatini.sidecar.usecase.authorization.AuthorizationResult;
 
 
 
/**
 * Sealed Interface für alle Pipeline-Ergebnisse.
 */
@RegisterForReflection(registerFullHierarchy = true)
public sealed interface ProcessingResult {
 
    record Skip() implements ProcessingResult {
    }
 
    record Proceed(AuthContext authContext) implements ProcessingResult {
    }
 
    record Forbidden(AuthorizationResult result) implements ProcessingResult {
    }
 
    record Unauthorized(String message) implements ProcessingResult {
    }
 
    record Error(String message) implements ProcessingResult {
    }
 
    static Skip skip() {
        return new Skip();
    }
 
    static Proceed proceed(AuthContext ctx) {
        return new Proceed(ctx);
    }
 
    static Forbidden forbidden(AuthorizationResult r) {
        return new Forbidden(r);
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
 
 
}
