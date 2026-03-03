package space.maatini.sidecar.service;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.client.RolesServiceClient;
import space.maatini.sidecar.config.SidecarConfig;
import space.maatini.sidecar.model.AuthContext;
import space.maatini.sidecar.model.RolesResponse;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class RolesServicePojoTest {

    private RolesService rolesService;
    private SidecarConfig config;
    private SidecarConfig.AuthzConfig authzConfig;
    private SidecarConfig.AuthzConfig.RolesServiceConfig rolesServiceConfig;
    private RolesServiceClient rolesServiceClient;

    @BeforeEach
    void setup() throws Exception {
        rolesService = new RolesService();

        config = mock(SidecarConfig.class);
        authzConfig = mock(SidecarConfig.AuthzConfig.class);
        rolesServiceConfig = mock(SidecarConfig.AuthzConfig.RolesServiceConfig.class);
        rolesServiceClient = mock(RolesServiceClient.class);

        when(config.authz()).thenReturn(authzConfig);
        when(authzConfig.rolesService()).thenReturn(rolesServiceConfig);
        when(rolesServiceConfig.enabled()).thenReturn(true);

        setField(rolesService, "config", config);
        setField(rolesService, "rolesServiceClient", rolesServiceClient);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void testEnrichWithRoles_Disabled() {
        when(rolesServiceConfig.enabled()).thenReturn(false);
        AuthContext auth = AuthContext.builder().userId("u").build();
        AuthContext result = rolesService.enrichWithRoles(auth).await().indefinitely();
        assertSame(auth, result);
    }

    @Test
    void testEnrichWithRoles_NullContext() {
        AuthContext result = rolesService.enrichWithRoles(null).await().indefinitely();
        assertFalse(result.isAuthenticated());
        assertEquals("anonymous", result.userId());
    }

    @Test
    void testEnrichWithRoles_NotAuthenticated() {
        AuthContext auth = AuthContext.anonymous();
        AuthContext result = rolesService.enrichWithRoles(auth).await().indefinitely();
        assertSame(auth, result);
    }

    @Test
    void testEnrichWithRoles_Success() {
        AuthContext auth = AuthContext.builder().userId("u1").tenant("t1").build();
        RolesResponse response = new RolesResponse("u1", Set.of("admin"), Set.of("read"), "t1");

        when(rolesServiceClient.getRolesForTenant("u1", "t1")).thenReturn(Uni.createFrom().item(response));

        AuthContext result = rolesService.enrichWithRoles(auth).await().indefinitely();

        assertTrue(result.roles().contains("admin"));
        assertTrue(result.permissions().contains("read"));
        assertEquals("t1", result.tenant());
    }

    @Test
    void testEnrichWithRoles_NullResponse() {
        AuthContext auth = AuthContext.builder().userId("u1").build();
        when(rolesServiceClient.getRoles("u1")).thenReturn(Uni.createFrom().nullItem());

        AuthContext result = rolesService.enrichWithRoles(auth).await().indefinitely();
        assertSame(auth, result);
    }

    @Test
    void testEnrichWithRoles_FailureFallback() {
        AuthContext auth = AuthContext.builder().userId("u1").build();
        when(rolesServiceClient.getRoles("u1"))
                .thenReturn(Uni.createFrom().failure(new RuntimeException("Network error")));

        AuthContext result = rolesService.enrichWithRoles(auth).await().indefinitely();
        assertSame(auth, result);
    }

    @Test
    void testFetchRoles_WithoutTenant() {
        RolesResponse response = new RolesResponse("u1", Set.of("r1"), Set.of(), null);
        when(rolesServiceClient.getRoles("u1")).thenReturn(Uni.createFrom().item(response));

        RolesResponse result = rolesService.fetchRoles("u1", null).await().indefinitely();
        assertEquals(response, result);
        verify(rolesServiceClient).getRoles("u1");
    }

    @Test
    void testFetchPermissions() {
        RolesResponse response = new RolesResponse("u1", Set.of(), Set.of("p1"), null);
        when(rolesServiceClient.getPermissions("u1")).thenReturn(Uni.createFrom().item(response));

        RolesResponse result = rolesService.fetchPermissions("u1").await().indefinitely();
        assertEquals(response, result);
    }

    @Test
    void testFallbackFetchRoles() {
        RolesResponse result = rolesService.fallbackFetchRoles("u1", "t1", new RuntimeException("err")).await()
                .indefinitely();
        assertTrue(result.roles().contains("offline-user"));
        assertEquals("t1", result.tenant());
    }

    @Test
    void testFallbackFetchPermissions() {
        RolesResponse result = rolesService.fallbackFetchPermissions("u1", new RuntimeException("err")).await()
                .indefinitely();
        assertTrue(result.permissions().contains("offline-read"));
    }
}
