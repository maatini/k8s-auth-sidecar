package de.edeka.eit.sidecar.application.service;
 
import io.smallrye.mutiny.Uni;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import de.edeka.eit.sidecar.infrastructure.config.SidecarConfig;
import de.edeka.eit.sidecar.domain.model.AuthContext;
import de.edeka.eit.sidecar.domain.model.AuthContext;
import de.edeka.eit.sidecar.domain.model.ProcessingResult;
import de.edeka.eit.sidecar.domain.model.SidecarRequest;
import de.edeka.eit.sidecar.usecase.authorization.AuthorizationResult;
import de.edeka.eit.sidecar.usecase.authorization.AuthorizationUseCase;
 
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
    private AuthorizationUseCase authorizationUseCase;
 
    @BeforeEach
    void setup() {
        config = mock(SidecarConfig.class);
        authService = mock(AuthenticationService.class);
        authorizationUseCase = mock(AuthorizationUseCase.class);
 
        processor = new SidecarRequestProcessor(authService, authorizationUseCase, config, "/q");
 
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
        when(authorizationUseCase.execute(any()))
                .thenReturn(Uni.createFrom().item(new AuthorizationResult(true, null, Collections.emptyList())));
 
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
        when(authorizationUseCase.execute(any()))
                .thenReturn(Uni.createFrom().item(new AuthorizationResult(false, "Denied by test", Collections.emptyList())));
 
        ProcessingResult result = processor.process(request).await().indefinitely();
        assertTrue(result instanceof ProcessingResult.Forbidden);
        assertEquals("Denied by test", ((ProcessingResult.Forbidden) result).result().reason());
    }
}
