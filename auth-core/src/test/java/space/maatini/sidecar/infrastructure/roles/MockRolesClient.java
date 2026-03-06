package space.maatini.sidecar.infrastructure.roles;
 
import io.quarkus.test.Mock;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import space.maatini.sidecar.domain.model.RolesResponse;
 
import java.util.Collections;
 
@Mock
@ApplicationScoped
@RestClient
public class MockRolesClient implements RolesClient {
    @Override
    public Uni<RolesResponse> getUserRoles(String userId) {
        return Uni.createFrom().item(new RolesResponse(userId, Collections.emptySet(), Collections.emptySet()));
    }
}
