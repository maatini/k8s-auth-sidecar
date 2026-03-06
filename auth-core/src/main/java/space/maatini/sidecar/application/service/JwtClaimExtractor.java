package space.maatini.sidecar.application.service;

import org.eclipse.microprofile.jwt.JsonWebToken;
import java.util.Map;

/**
 * Interface for extracting claims from JWT tokens.
 */
public interface JwtClaimExtractor {

    <T> T extractClaim(JsonWebToken jwt, String claimName, Class<T> type);

    long extractLongClaim(JsonWebToken jwt, String claimName);

    Map<String, Object> extractAllClaims(JsonWebToken jwt);
}
