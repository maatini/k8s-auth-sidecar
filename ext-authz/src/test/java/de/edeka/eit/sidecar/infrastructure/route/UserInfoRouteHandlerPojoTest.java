package de.edeka.eit.sidecar.infrastructure.route;

import com.fasterxml.jackson.core.JsonProcessingException;
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
import org.mockito.ArgumentCaptor;
import de.edeka.eit.sidecar.application.service.SidecarRequestProcessor;
import de.edeka.eit.sidecar.application.service.UserInfoResponseBuilder;
import de.edeka.eit.sidecar.domain.model.AuthContext;
import de.edeka.eit.sidecar.domain.model.ProcessingResult;
import de.edeka.eit.sidecar.domain.model.UserInfoResponse;
import de.edeka.eit.sidecar.infrastructure.security.HeaderSanitizer;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
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
                false, "Not authorized", List.of(), Collections.emptySet());
        ProcessingResult.Forbidden forbidden = new ProcessingResult.Forbidden(authResult);

        when(processor.process(any())).thenReturn(Uni.createFrom().item(forbidden));

        handler.userinfo(ctx).await().indefinitely();

        verify(vertxResponse).setStatusCode(403);
    }

    // --- New test cases ---

    @Test
    void userinfo_WhenAuthenticated_ResponseBodyContainsExpectedFields() {
        AuthContext authCtx = AuthContext.builder()
                .userId("bob")
                .name("Bob Smith")
                .preferredUsername("bob")
                .email("bob@test.com")
                .roles(Set.of("viewer"))
                .permissions(Set.of("reports:read"))
                .issuedAt(1000L)
                .expiresAt(2000L)
                .build();
        ProcessingResult.Proceed proceed = new ProcessingResult.Proceed(authCtx);

        UserInfoResponse userInfo = new UserInfoResponse(
                "bob", "Bob Smith", "bob", "bob@test.com",
                List.of("viewer"), List.of("reports:read"),
                Map.of("reports", List.of("read")), 2000L, 1000L);

        when(processor.process(any())).thenReturn(Uni.createFrom().item(proceed));
        when(responseBuilder.build(authCtx)).thenReturn(userInfo);

        handler.userinfo(ctx).await().indefinitely();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(vertxResponse).end(bodyCaptor.capture());

        String json = bodyCaptor.getValue();
        assertTrue(json.contains("\"sub\":\"bob\""), "Body should contain sub");
        assertTrue(json.contains("\"name\":\"Bob Smith\""), "Body should contain name");
        assertTrue(json.contains("\"email\":\"bob@test.com\""), "Body should contain email");
        assertTrue(json.contains("\"roles\":[\"viewer\"]"), "Body should contain roles");
        assertTrue(json.contains("\"reports\""), "Body should contain permissions key");
    }

    @Test
    void userinfo_WhenForbiddenWithNullResult_Returns403WithDefaultMessage() {
        ProcessingResult.Forbidden forbidden = new ProcessingResult.Forbidden(null);

        when(processor.process(any())).thenReturn(Uni.createFrom().item(forbidden));

        handler.userinfo(ctx).await().indefinitely();

        verify(vertxResponse).setStatusCode(403);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(vertxResponse).end(bodyCaptor.capture());
        assertTrue(bodyCaptor.getValue().contains("Access denied"),
                "Forbidden with null result should use default message");
    }

    @Test
    void userinfo_WhenSerializationFails_Returns500() throws Exception {
        AuthContext authCtx = AuthContext.builder().userId("alice").build();
        ProcessingResult.Proceed proceed = new ProcessingResult.Proceed(authCtx);

        UserInfoResponse userInfo = new UserInfoResponse(
                "alice", null, null, null,
                List.of(), List.of(), Map.of(), 0L, 0L);

        // Use a mocked ObjectMapper that throws on serialization
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any()))
                .thenThrow(new JsonProcessingException("Simulated error") {});

        UserInfoRouteHandler failHandler = new UserInfoRouteHandler(
                processor, responseBuilder, jwt, headerSanitizer, failingMapper);

        when(processor.process(any())).thenReturn(Uni.createFrom().item(proceed));
        when(responseBuilder.build(authCtx)).thenReturn(userInfo);

        failHandler.userinfo(ctx).await().indefinitely();

        verify(vertxResponse).setStatusCode(500);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(vertxResponse).end(bodyCaptor.capture());
        assertTrue(bodyCaptor.getValue().contains("Internal Server Error"));
    }

    @Test
    void userinfo_WhenSkipResult_Returns200WithEmptyJson() {
        ProcessingResult.Skip skip = new ProcessingResult.Skip();

        when(processor.process(any())).thenReturn(Uni.createFrom().item(skip));

        handler.userinfo(ctx).await().indefinitely();

        verify(vertxResponse).setStatusCode(200);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(vertxResponse).end(bodyCaptor.capture());
        assertEquals("{}", bodyCaptor.getValue());
    }

    @Test
    void userinfo_SetsContentTypeJsonForEveryResult() {
        // Test with Unauthorized path
        ProcessingResult.Unauthorized unauthorized = new ProcessingResult.Unauthorized("no token");

        when(processor.process(any())).thenReturn(Uni.createFrom().item(unauthorized));

        handler.userinfo(ctx).await().indefinitely();

        verify(vertxResponse).putHeader("Content-Type", "application/json");
    }

    @Test
    void userinfo_WhenUnauthorized_ResponseBodyContainsReason() {
        ProcessingResult.Unauthorized unauthorized = new ProcessingResult.Unauthorized("Token expired");

        when(processor.process(any())).thenReturn(Uni.createFrom().item(unauthorized));

        handler.userinfo(ctx).await().indefinitely();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(vertxResponse).end(bodyCaptor.capture());

        String body = bodyCaptor.getValue();
        assertTrue(body.contains("Unauthorized"), "Body should contain 'Unauthorized'");
        assertTrue(body.contains("Token expired"), "Body should contain the reason");
    }

    @Test
    void userinfo_WhenError_ResponseBodyContainsReason() {
        ProcessingResult.Error error = new ProcessingResult.Error("OPA timeout");

        when(processor.process(any())).thenReturn(Uni.createFrom().item(error));

        handler.userinfo(ctx).await().indefinitely();

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(vertxResponse).end(bodyCaptor.capture());

        String body = bodyCaptor.getValue();
        assertTrue(body.contains("Internal Server Error"), "Body should contain error message");
        assertTrue(body.contains("OPA timeout"), "Body should contain the reason");
    }

    @Test
    void userinfo_FiltersEnvoyInternalHeaders() {
        // Set up headers including an Envoy internal header
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("authorization", "Bearer xyz");
        headers.add("x-envoy-internal", "true");
        when(vertxRequest.headers()).thenReturn(headers);

        // Mark x-envoy-internal as an internal header to be filtered
        when(headerSanitizer.isEnvoyInternalHeader("x-envoy-internal")).thenReturn(true);
        when(headerSanitizer.isEnvoyInternalHeader("authorization")).thenReturn(false);

        ProcessingResult.Unauthorized unauthorized = new ProcessingResult.Unauthorized("test");
        when(processor.process(any())).thenReturn(Uni.createFrom().item(unauthorized));

        handler.userinfo(ctx).await().indefinitely();

        // Verify the Envoy header filter was called
        verify(headerSanitizer, atLeastOnce()).isEnvoyInternalHeader("x-envoy-internal");
    }
}
