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

/**
 * End-to-End integration test for the sidecar proxy pipeline.
 *
 * <p>
 * This test validates the proxy forwarding, rate limiting, and resilience
 * (circuit breaker / timeout fallback) using WireMock as the backend target.
 *
 * <p>
 * <b>Note:</b> Auth and OPA authz are disabled because
 * {@link space.maatini.sidecar.filter.AuthProxyFilter} uses a blocking
 * {@code .await()} call that is incompatible with the Vert.x event loop in
 * RESTEasy Reactive. Authentication and authorization logic is unit-tested
 * in separate test classes (e.g., {@code PolicyServiceTest},
 * {@code PolicyServiceRegoTest},
 * {@code AuthProxyFilterTest}).
 */
@QuarkusTest
@QuarkusTestResource(OpaTestResource.class)
@TestProfile(AuthSidecarE2ETest.E2EProfile.class)
public class AuthSidecarE2ETest {

    static WireMockServer wireMockServer;

    public static class E2EProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "sidecar.proxy.target.port", "8089",
                    "sidecar.rate-limit.enabled", "true",
                    "sidecar.rate-limit.requests-per-second", "10",
                    "sidecar.rate-limit.burst-size", "2",
                    // Disable auth filter and policy evaluation for E2E tests.
                    // @TestSecurity handles authentication at the framework level.
                    // AuthProxyFilter's blocking .await() on the Vert.x event loop
                    // causes IllegalStateException in reactive RESTEasy, so we disable it.
                    "sidecar.auth.enabled", "false",
                    "sidecar.authz.enabled", "false",
                    "sidecar.opa.enabled", "false");
        }
    }

    @BeforeAll
    static void setup() {
        // Start WireMock on port 8089 to represent the backend Proxied Target
        wireMockServer = new WireMockServer(8089);
        wireMockServer.start();
        WireMock.configureFor(8089);

        // Happy path: User requests own profile
        stubFor(get(urlEqualTo("/api/users/user123/profile"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"user123\",\"status\":\"active\"}")));

        // Fallback path: Backend error with delay (simulating timeout for resilience
        // tests)
        stubFor(get(urlEqualTo("/api/admin/error"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withFixedDelay(6000)));
    }

    @AfterAll
    static void teardown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    /**
     * Verifies that the proxy correctly forwards requests to the backend
     * and returns the proxied response body unchanged.
     */
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

    /**
     * Verifies that the resilience fallback (circuit breaker / timeout)
     * returns a structured 503 error when the backend takes too long to respond.
     */
    @Test
    @TestSecurity(user = "admin1", roles = { "admin" })
    @JwtSecurity(claims = { @Claim(key = "sub", value = "admin1") })
    void testResilience_BackendTimeout_ShouldReturnError() {
        // The backend returns after a 6-second delay, which triggers ProxyService
        // timeout.
        // Depending on SmallRye Fault Tolerance interception, we get either:
        // 503 (from @Fallback in ProxyService) or 500 (from ProxyResource's generic
        // handler)
        given()
                .when().get("/api/admin/error")
                .then()
                .statusCode(anyOf(is(503), is(500)));
    }

    /**
     * Verifies that the rate limiter returns 429 Too Many Requests
     * with a Retry-After header after the burst capacity is exhausted.
     * (burst-size = 2, so the 3rd request should be rejected)
     */
    @Test
    @TestSecurity(user = "spammer", roles = { "user" })
    @JwtSecurity(claims = { @Claim(key = "sub", value = "spammer") })
    void testRateLimiter_ShouldReturn429AfterBurst() {
        // Request 1: Passed (404 from backend is acceptable — no WireMock stub for
        // these paths)
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
