package space.maatini.sidecar.application.service;
 
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.domain.model.PolicyDecision;
import space.maatini.sidecar.domain.model.ProcessingResult;
import space.maatini.sidecar.domain.model.SidecarRequest;
 
import java.util.Collections;
import java.util.List;
import java.util.Map;
 
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
 
class SidecarRequestProcessorPojoTest {
 
    private SidecarRequestProcessor processor;
    private SidecarConfig config;
    private AuthenticationService authService;
    private PolicyEngine policyEngine;
    private RolesService rolesService;
 
    @BeforeEach
    void setup() {
        config = mock(SidecarConfig.class);
        authService = mock(AuthenticationService.class);
        policyEngine = mock(PolicyEngine.class);
        rolesService = mock(RolesService.class);

        processor = new SidecarRequestProcessor(authService, rolesService, policyEngine, config);
 
        // Common config
        SidecarConfig.AuthConfig authConfig = mock(SidecarConfig.AuthConfig.class);
        SidecarConfig.AuthzConfig authzConfig = mock(SidecarConfig.AuthzConfig.class);
        when(config.auth()).thenReturn(authConfig);
        when(config.authz()).thenReturn(authzConfig);
        when(authConfig.enabled()).thenReturn(true);
        when(authzConfig.enabled()).thenReturn(true);
        when(authConfig.publicPaths()).thenReturn(Collections.emptyList());

        processor.init();
    }

 
    @Test
    void testProcess_InternalPath() {
        SidecarRequest request = new SidecarRequest("GET", "/q/health", Map.of(), Map.of(), null);
 
        ProcessingResult result = processor.process(request).await().indefinitely();
        assertTrue(result instanceof ProcessingResult.Skip);
        verify(authService, never()).extractAuthContext(any());
    }
 
    @Test
    void testProcess_PublicPath() {
        when(config.auth().publicPaths()).thenReturn(List.of("/health"));
        SidecarRequest request = new SidecarRequest("GET", "/health", Map.of(), Map.of(), null);
 
        ProcessingResult result = processor.process(request).await().indefinitely();
        assertTrue(result instanceof ProcessingResult.Skip);
        verify(authService, never()).extractAuthContext(any());
    }
 
    @Test
    void testProcess_AuthDisabled() {
        when(config.auth().enabled()).thenReturn(false);
        SidecarRequest request = new SidecarRequest("GET", "/api/data", Map.of(), Map.of(), null);
 
        ProcessingResult result = processor.process(request).await().indefinitely();
        assertTrue(result instanceof ProcessingResult.Proceed);
        assertEquals("anonymous", ((ProcessingResult.Proceed) result).authContext().userId());
    }
 
    @Test
    void testProcess_AuthFailure() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        SidecarRequest request = new SidecarRequest("GET", "/api/data", Map.of(), Map.of(), jwt);
 
        when(authService.extractAuthContext(eq(jwt)))
                .thenReturn(Uni.createFrom().item(AuthContext.anonymous()));
 
        ProcessingResult result = processor.process(request).await().indefinitely();
        assertTrue(result instanceof ProcessingResult.Unauthorized);
        assertEquals("Authentication required", ((ProcessingResult.Unauthorized) result).message());
    }
 
    @Test
    void testProcess_Success() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        SidecarRequest request = new SidecarRequest("GET", "/api/data", Map.of(), Map.of(), jwt);
 
        AuthContext authCtx = AuthContext.builder().userId("u123").email("u@u").build();
        when(authService.extractAuthContext(eq(jwt))).thenReturn(Uni.createFrom().item(authCtx));
        when(rolesService.enrich(any())).thenReturn(Uni.createFrom().item(authCtx));
        when(policyEngine.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(PolicyDecision.allow()));
 
        ProcessingResult result = processor.process(request).await().indefinitely();
        assertTrue(result instanceof ProcessingResult.Proceed);
        assertEquals("u123", ((ProcessingResult.Proceed) result).authContext().userId());
    }
 
    @Test
    void testProcess_Forbidden() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        SidecarRequest request = new SidecarRequest("GET", "/api/data", Map.of(), Map.of(), jwt);
 
        AuthContext authCtx = AuthContext.builder().userId("u123").email("u@u").build();
        when(authService.extractAuthContext(eq(jwt))).thenReturn(Uni.createFrom().item(authCtx));
        when(rolesService.enrich(any())).thenReturn(Uni.createFrom().item(authCtx));
        when(policyEngine.evaluate(any(), any(), any(), any(), any()))
                .thenReturn(Uni.createFrom().item(PolicyDecision.deny("Denied by test")));
 
        ProcessingResult result = processor.process(request).await().indefinitely();
        assertTrue(result instanceof ProcessingResult.Forbidden);
        assertEquals("Denied by test", ((ProcessingResult.Forbidden) result).decision().reason());
    }
}
