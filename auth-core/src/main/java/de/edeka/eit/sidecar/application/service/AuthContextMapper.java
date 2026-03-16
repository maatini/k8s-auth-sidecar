package de.edeka.eit.sidecar.application.service;

import org.eclipse.microprofile.jwt.JsonWebToken;
import de.edeka.eit.sidecar.domain.model.AuthContext;
import java.util.Set;

/**
 * Interface for mapping JWT and extracted roles to AuthContext.
 */
public interface AuthContextMapper {

    AuthContext mapToAuthContext(JsonWebToken jwt, Set<String> roles);
}
