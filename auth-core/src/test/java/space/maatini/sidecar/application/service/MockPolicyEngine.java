package space.maatini.sidecar.application.service;
 
import io.quarkus.test.Mock;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.domain.model.PolicyDecision;
 
import java.util.Map;
 
@Mock
@ApplicationScoped
public class MockPolicyEngine implements PolicyEngine {
    @Override
    public Uni<PolicyDecision> evaluate(AuthContext context, String method, String path, Map<String, String> headers, Map<String, String> queryParams) {
        return Uni.createFrom().item(PolicyDecision.allow());
    }
}
