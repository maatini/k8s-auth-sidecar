package space.maatini.sidecar.application.service;

import org.eclipse.microprofile.jwt.JsonWebToken;
import space.maatini.sidecar.domain.model.AuthContext;
import java.util.Set;

/**
 * Interface for mapping JWT and extracted roles to AuthContext.
 */
public interface AuthContextMapper {

    AuthContext mapToAuthContext(JsonWebToken jwt, Set<String> roles);
}
