package space.maatini.sidecar.application.service;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.infrastructure.roles.RolesClient;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.domain.model.RolesResponse;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class RolesServicePojoTest {

    private RolesService rolesService;
    private RolesClient mockClient;
    private SidecarConfig mockConfig;
    private SidecarConfig.RolesConfig rolesConfig;

    @BeforeEach
    void setup() {
        rolesService = new RolesService();
        mockClient = mock(RolesClient.class);
        mockConfig = mock(SidecarConfig.class);
        rolesConfig = mock(SidecarConfig.RolesConfig.class);

        rolesService.rolesClient = mockClient;
        rolesService.config = mockConfig;
        when(mockConfig.roles()).thenReturn(rolesConfig);
    }

    @Test
    void testEnrich_Disabled() {
        when(rolesConfig.enabled()).thenReturn(false);
        AuthContext context = AuthContext.builder().userId("u1").roles(Set.of("user")).build();

        AuthContext result = rolesService.enrich(context).await().indefinitely();

        assertSame(context, result);
        verifyNoInteractions(mockClient);
    }

    @Test
    void testEnrich_Anonymous() {
        when(rolesConfig.enabled()).thenReturn(true);
        AuthContext context = AuthContext.anonymous();

        AuthContext result = rolesService.enrich(context).await().indefinitely();

        assertSame(context, result);
        verifyNoInteractions(mockClient);
    }

    @Test
    void testEnrich_Success() {
        when(rolesConfig.enabled()).thenReturn(true);
        AuthContext context = AuthContext.builder().userId("u1").roles(Set.of("user")).build();
        RolesResponse response = new RolesResponse("u1", Set.of("admin"), Set.of("read"));

        when(mockClient.getUserRoles("u1")).thenReturn(Uni.createFrom().item(response));

        AuthContext result = rolesService.enrich(context).await().indefinitely();

        assertTrue(result.roles().contains("user"));
        assertTrue(result.roles().contains("admin"));
        assertTrue(result.permissions().contains("read"));
    }

    @Test
    void testEnrich_Failure_FailClosed() {
        when(rolesConfig.enabled()).thenReturn(true);
        AuthContext context = AuthContext.builder().userId("u1").roles(Set.of("user")).build();

        when(mockClient.getUserRoles("u1")).thenReturn(Uni.createFrom().failure(new RuntimeException("API Down")));

        assertThrows(SecurityException.class, () -> 
            rolesService.enrich(context).await().indefinitely()
        );
    }

    @Test
    void testEnrich_EmptyResponse() {
        when(rolesConfig.enabled()).thenReturn(true);
        AuthContext context = AuthContext.builder().userId("u1").roles(Set.of("user")).build();

        when(mockClient.getUserRoles("u1")).thenReturn(Uni.createFrom().item(new RolesResponse("u1", null, null)));

        AuthContext result = rolesService.enrich(context).await().indefinitely();

        assertEquals(Set.of("user"), result.roles());
        assertTrue(result.permissions().isEmpty());
    }
}
