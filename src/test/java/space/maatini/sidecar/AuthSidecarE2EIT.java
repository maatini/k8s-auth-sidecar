package space.maatini.sidecar;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.test.OpaTestResource;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;

@QuarkusTest
@QuarkusTestResource(OpaTestResource.class)
@TestProfile(AuthSidecarE2EIT.E2EProfile.class)
public class AuthSidecarE2EIT {

    static WireMockServer wireMockServer;

    public static class E2EProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "sidecar.proxy.target.port", "8089",
                    "sidecar.rate-limit.enabled", "true",
                    "sidecar.rate-limit.requests-per-second", "10",
                    "sidecar.rate-limit.burst-size", "2",
                    // Disable external roles service so we only rely on the JWT context for tests
                    "sidecar.authz.roles-service.enabled", "false",
                    // Force auth validation on for tests
                    "sidecar.auth.enabled", "true",
                    "sidecar.authz.enabled", "true",
                    // Ensure Opa is evaluated
                    "sidecar.opa.enabled", "true");
        }
    }

    @BeforeAll
    static void setup() {
        // Start WireMock on port 8089 to represent the backend Proxied Target
        wireMockServer = new WireMockServer(8089);
        wireMockServer.start();
        WireMock.configureFor(8089);

        // 1. Happy path: User requests own profile
        stubFor(get(urlEqualTo("/api/users/user123/profile"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"user123\",\"status\":\"active\"}")));

        // 2. Fallback path: Admin backend error (simulating timeout/error for
        // resilience tests)
        stubFor(get(urlEqualTo("/api/admin/error"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withFixedDelay(6000))); // This will trigger CircuitBreaker/Timeout fallback in ProxyService
    }

    @AfterAll
    static void teardown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void testUnauthenticated_ShouldReturn401() {
        given()
                .when().get("/api/users/user123/profile")
                .then()
                .statusCode(401)
                .body("code", is("unauthorized"));
    }

    @Test
    @TestSecurity(user = "user123", roles = { "user" })
    @JwtSecurity(claims = {
            @Claim(key = "sub", value = "user123"),
            @Claim(key = "email", value = "user123@example.com")
    })
    void testHappyPath_OwnProfile_ShouldReturn200() {
        given()
                .when().get("/api/users/user123/profile")
                .then()
                .statusCode(200)
                .body("id", is("user123"));
    }

    @Test
    @TestSecurity(user = "user456", roles = { "user" })
    @JwtSecurity(claims = {
            @Claim(key = "sub", value = "user456")
    })
    void testForbidden_AccessingOtherUserProfile_ShouldReturn403() {
        given()
                .when().get("/api/users/user123/profile") // context is user456, trying to access user123
                .then()
                .statusCode(403)
                .body("code", is("forbidden"));
    }

    @Test
    @TestSecurity(user = "admin1", roles = { "admin" })
    @JwtSecurity(claims = { @Claim(key = "sub", value = "admin1") })
    void testResilience_BackendTimeout_ShouldTriggerFallback503() {
        // As admin, they have authorization to access /api/admin/*
        // However, the backend responds with delay, triggering the fallback in
        // ProxyService
        given()
                .when().get("/api/admin/error")
                .then()
                .statusCode(503)
                // Expecting our custom JSON fallback structure from ProxyService
                .body("error", containsString("Service Unavailable"));
    }

    @Test
    @TestSecurity(user = "spammer", roles = { "user" })
    @JwtSecurity(claims = { @Claim(key = "sub", value = "spammer") })
    void testRateLimiter_ShouldReturn429AfterBurst() {
        // The burst size is 2 in config overrides

        // Request 1: Passed (RateLimitFilter matches, Authorization is checked, Proxy
        // acts. 404 from backend is fine)
        given().when().get("/api/users/spammer/spam1").then().statusCode(anyOf(is(200), is(404)));

        // Request 2: Passed
        given().when().get("/api/users/spammer/spam2").then().statusCode(anyOf(is(200), is(404)));

        // Request 3: Rate Limited (429)
        given().when().get("/api/users/spammer/spam3")
                .then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                .body("code", is("too_many_requests"));
    }
}
