package space.maatini.sidecar.usecase.authorization;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.application.service.PolicyEngine;
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.domain.model.PolicyDecision;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthorizationUseCasePojoTest {

    private PolicyEngine policyEngine;
    private AuthorizationUseCase useCase;
    private AuthContext mockContext;

    @BeforeEach
    void setUp() {
        policyEngine = mock(PolicyEngine.class);
        useCase = new AuthorizationUseCase(policyEngine);
        mockContext = AuthContext.builder().userId("test-user").roles(java.util.Set.of("user")).build();
    }

    @Test
    void execute_WhenPolicyEngineAllows_ReturnsAllowedResult() {
        AuthorizationCommand command = new AuthorizationCommand(
                mockContext,
                "GET",
                "/api/data",
                Map.of("Header1", "Val1"),
                Map.of("Param1", "Val1")
        );

        when(policyEngine.evaluate(
                eq(command.context()),
                eq(command.method()),
                eq(command.path()),
                eq(command.headers()),
                eq(command.queryParams())
        )).thenReturn(Uni.createFrom().item(PolicyDecision.allow()));

        AuthorizationResult result = useCase.execute(Uni.createFrom().item(command))
                .await().indefinitely();

        assertTrue(result.allowed());
        assertNull(result.reason());
        assertTrue(result.violations().isEmpty());
        // Verify all fields are passed correctly
        verify(policyEngine).evaluate(
                command.context(),
                command.method(),
                command.path(),
                command.headers(),
                command.queryParams()
        );
    }

    @Test
    void execute_WhenPolicyEngineDeniesWithReason_ReturnsDeniedResult() {
        AuthorizationCommand command = new AuthorizationCommand(
                mockContext,
                "DELETE",
                "/api/data",
                Map.of(),
                Map.of()
        );

        when(policyEngine.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(PolicyDecision.deny("Not authorized")));

        AuthorizationResult result = useCase.execute(Uni.createFrom().item(command))
                .await().indefinitely();

        assertFalse(result.allowed());
        assertEquals("Not authorized", result.reason());
        assertTrue(result.violations().isEmpty());
    }

    @Test
    void execute_WhenPolicyEngineDeniesWithViolations_ReturnsDeniedResult() {
        AuthorizationCommand command = new AuthorizationCommand(
                mockContext,
                "POST",
                "/api/secure",
                Map.of(),
                Map.of()
        );

        when(policyEngine.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(PolicyDecision.deny("Violation occurred", List.of("Role mismatch", "Invalid scoping"))));

        AuthorizationResult result = useCase.execute(Uni.createFrom().item(command))
                .await().indefinitely();

        assertFalse(result.allowed());
        assertEquals("Violation occurred", result.reason());
        assertEquals(2, result.violations().size());
        assertTrue(result.violations().contains("Role mismatch"));
        assertTrue(result.violations().contains("Invalid scoping"));
    }
}
