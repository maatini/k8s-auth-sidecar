package space.maatini.sidecar.service;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import space.maatini.sidecar.client.RolesServiceClient;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.model.RolesResponse;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(RolesServiceTest.Profile.class)
class RolesServiceTest {

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                "sidecar.authz.roles-service.enabled", "true",
                "quarkus.oidc.tenant-enabled", "true"
            );
        }
    }

    @Inject
    RolesService rolesService;

    @InjectMock
    @RestClient
    RolesServiceClient rolesServiceClient;

    @Test
    void testEnrichWithRoles_Success() {
        AuthContext initialContext = AuthContext.builder()
            .userId("user123")
            .roles(Set.of("user")) // Hat schon eine Rolle
            .build();

        // Mock externen Service Response
        RolesResponse response = new RolesResponse(
            "user123",
            Set.of("admin"), // Neue Rolle
            Set.of("read:all"), // Permission
            "tenant-1"
        );

        when(rolesServiceClient.getRoles("user123"))
            .thenReturn(Uni.createFrom().item(response));

        AuthContext enriched = rolesService.enrichWithRoles(initialContext).await().indefinitely();

        assertNotNull(enriched);
        assertEquals("user123", enriched.userId());
        assertTrue(enriched.roles().contains("user")); // Original erhalten
        assertTrue(enriched.roles().contains("admin")); // Neue hinzugefügt
        assertTrue(enriched.permissions().contains("read:all"));
        assertEquals("tenant-1", enriched.tenant());
    }

    @Test
    void testEnrichWithRoles_ServiceFailure() {
        AuthContext initialContext = AuthContext.builder()
            .userId("user-fail")
            .roles(Set.of("user"))
            .build();

        // Simuliere Service-Fehler
        when(rolesServiceClient.getRoles("user-fail"))
            .thenReturn(Uni.createFrom().failure(new RuntimeException("Service down")));

        AuthContext result = rolesService.enrichWithRoles(initialContext).await().indefinitely();

        // Sollte originalen Context zurückgeben (Fallback)
        assertNotNull(result);
        assertEquals(1, result.roles().size());
        assertTrue(result.roles().contains("user"));
    }

    @Test
    void testEnrichWithRoles_NullContext() {
        AuthContext result = rolesService.enrichWithRoles(null).await().indefinitely();
        assertNotNull(result);
        assertEquals("anonymous", result.userId());
        assertFalse(result.isAuthenticated());
    }
}
