package de.edeka.eit.sidecar.application.service;

import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;
import de.edeka.eit.sidecar.domain.model.AuthContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of AuthContextMapper.
 */
@ApplicationScoped
@RegisterForReflection
public class AuthContextMapperImpl implements AuthContextMapper {

    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_NAME = "name";
    private static final String CLAIM_PREFERRED_USERNAME = "preferred_username";
    private static final String CLAIM_IAT = "iat";
    private static final String CLAIM_EXP = "exp";
    private static final String CLAIM_JTI = "jti";

    @Inject
    JwtClaimExtractor claimExtractor;

    @Override
    public AuthContext mapToAuthContext(JsonWebToken jwt, Set<String> roles) {
        if (jwt == null) {
            return AuthContext.anonymous();
        }

        String userId = jwt.getSubject();
        String email = claimExtractor.extractClaim(jwt, CLAIM_EMAIL, String.class);
        String name = claimExtractor.extractClaim(jwt, CLAIM_NAME, String.class);
        String preferredUsername = claimExtractor.extractClaim(jwt, CLAIM_PREFERRED_USERNAME, String.class);

        List<String> audience = extractAudience(jwt);

        long issuedAt = claimExtractor.extractLongClaim(jwt, CLAIM_IAT);
        long expiresAt = claimExtractor.extractLongClaim(jwt, CLAIM_EXP);
        String tokenId = claimExtractor.extractClaim(jwt, CLAIM_JTI, String.class);

        Map<String, Object> claims = claimExtractor.extractAllClaims(jwt);

        return AuthContext.builder()
                .userId(userId)
                .email(email)
                .name(name)
                .preferredUsername(preferredUsername)
                .issuer(jwt.getIssuer())
                .audience(audience)
                .roles(roles)
                .permissions(Collections.emptySet())
                .claims(claims)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .tokenId(tokenId)
                .build();
    }

    private List<String> extractAudience(JsonWebToken jwt) {
        Set<String> audience;
        try {
            audience = jwt.getAudience();
        } catch (Exception e) {
            return Collections.emptyList();
        }
        return audience != null ? new ArrayList<>(audience) : Collections.emptyList();
    }
}
