package de.edeka.eit.sidecar.application.service;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Implementation of JwtClaimExtractor using MicroProfile JWT.
 */
@ApplicationScoped
@RegisterForReflection
public class JwtClaimExtractorImpl implements JwtClaimExtractor {

    private static final Logger LOG = Logger.getLogger(JwtClaimExtractorImpl.class);

    @Override
    @SuppressWarnings("unchecked")
    public <T> T extractClaim(JsonWebToken jwt, String claimName, Class<T> type) {
        try {
            return (T) jwt.getClaim(claimName);
        } catch (Exception e) {
            LOG.debugf("Failed to extract claim '%s': %s", claimName, e.getMessage());
            return null;
        }
    }

    @Override
    public long extractLongClaim(JsonWebToken jwt, String claimName) {
        Object value;
        try {
            value = jwt.getClaim(claimName);
        } catch (Exception e) {
            LOG.debugf("Failed to extract long claim '%s': %s", claimName, e.getMessage());
            return 0;
        }

        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0;
    }

    @Override
    public Map<String, Object> extractAllClaims(JsonWebToken jwt) {
        Map<String, Object> claims = new HashMap<>();
        try {
            for (String claimName : jwt.getClaimNames()) {
                Object value = jwt.getClaim(claimName);
                if (value != null) {
                    claims.put(claimName, value);
                }
            }
        } catch (Exception e) {
            LOG.debugf("Failed to extract all claims: %s", e.getMessage());
        }
        return claims;
    }
}
