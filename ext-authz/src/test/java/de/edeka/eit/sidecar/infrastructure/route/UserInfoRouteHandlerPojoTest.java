package de.edeka.eit.sidecar.infrastructure.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import de.edeka.eit.sidecar.application.service.SidecarRequestProcessor;
import de.edeka.eit.sidecar.application.service.UserInfoResponseBuilder;
import de.edeka.eit.sidecar.domain.model.AuthContext;
import de.edeka.eit.sidecar.domain.model.ProcessingResult;
import de.edeka.eit.sidecar.domain.model.UserInfoResponse;
import de.edeka.eit.sidecar.infrastructure.security.HeaderSanitizer;

import java.util.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * POJO tests for UserInfoRouteHandler with mocked dependencies.
 */
class UserInfoRouteHandlerPojoTest {

    private SidecarRequestProcessor processor;
    private UserInfoResponseBuilder responseBuilder;
    private JsonWebToken jwt;
    private HeaderSanitizer headerSanitizer;
    private ObjectMapper objectMapper;
    private UserInfoRouteHandler handler;

    // Mocked Vert.x objects
    private RoutingContext ctx;
    private HttpServerRequest vertxRequest;
    private HttpServerResponse vertxResponse;

    @BeforeEach
    void setUp() {
        processor = mock(SidecarRequestProcessor.class);
        responseBuilder = mock(UserInfoResponseBuilder.class);
        jwt = mock(JsonWebToken.class);
        headerSanitizer = mock(HeaderSanitizer.class);
        objectMapper = new ObjectMapper();

        handler = new UserInfoRouteHandler(processor, responseBuilder, jwt, headerSanitizer, objectMapper);

        ctx = mock(RoutingContext.class);
        vertxRequest = mock(HttpServerRequest.class);
        vertxResponse = mock(HttpServerResponse.class);

        when(ctx.request()).thenReturn(vertxRequest);
        when(ctx.response()).thenReturn(vertxResponse);
        when(ctx.queryParams()).thenReturn(MultiMap.caseInsensitiveMultiMap());

        when(headerSanitizer.extractPath(any())).thenReturn("/userinfo");
        when(headerSanitizer.extractMethod(any())).thenReturn("GET");
        when(headerSanitizer.isEnvoyInternalHeader(any())).thenReturn(false);

        when(vertxRequest.headers()).thenReturn(MultiMap.caseInsensitiveMultiMap());

        when(vertxResponse.putHeader(anyString(), anyString())).thenReturn(vertxResponse);
        when(vertxResponse.setStatusCode(anyInt())).thenReturn(vertxResponse);
        when(vertxResponse.end(anyString())).thenReturn(Future.succeededFuture());
    }

    @Test
    void userinfo_WhenAuthenticated_Returns200WithUserInfo() {
        AuthContext authCtx = AuthContext.builder()
                .userId("alice")
                .name("Alice")
                .email("alice@test.com")
                .roles(Set.of("admin"))
                .permissions(Set.of("orders:read"))
                .build();
        ProcessingResult.Proceed proceed = new ProcessingResult.Proceed(authCtx);

        UserInfoResponse userInfo = new UserInfoResponse(
                "alice", "Alice", null, "alice@test.com",
                List.of("admin"), List.of("orders:read"),
                Map.of("orders", List.of("read")), 0L, 0L);

        when(processor.process(any())).thenReturn(Uni.createFrom().item(proceed));
        when(responseBuilder.build(authCtx)).thenReturn(userInfo);

        handler.userinfo(ctx).await().indefinitely();

        verify(vertxResponse).setStatusCode(200);
        verify(vertxResponse).end(anyString());
    }

    @Test
    void userinfo_WhenUnauthenticated_Returns401() {
        ProcessingResult.Unauthorized unauthorized = new ProcessingResult.Unauthorized("Authentication required");

        when(processor.process(any())).thenReturn(Uni.createFrom().item(unauthorized));

        handler.userinfo(ctx).await().indefinitely();

        verify(vertxResponse).setStatusCode(401);
    }

    @Test
    void userinfo_WhenError_Returns500() {
        ProcessingResult.Error error = new ProcessingResult.Error("Internal server error");

        when(processor.process(any())).thenReturn(Uni.createFrom().item(error));

        handler.userinfo(ctx).await().indefinitely();

        verify(vertxResponse).setStatusCode(500);
    }

    @Test
    void userinfo_WhenForbidden_Returns403() {
        var authResult = new de.edeka.eit.sidecar.usecase.authorization.AuthorizationResult(
                false, "Not authorized", List.of());
        ProcessingResult.Forbidden forbidden = new ProcessingResult.Forbidden(authResult);

        when(processor.process(any())).thenReturn(Uni.createFrom().item(forbidden));

        handler.userinfo(ctx).await().indefinitely();

        verify(vertxResponse).setStatusCode(403);
    }
}
