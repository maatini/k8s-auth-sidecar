package space.maatini.sidecar.filter;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;

@QuarkusTest
@TestProfile(RateLimitFilterTest.Profile.class)
class RateLimitFilterTest {

    static WireMockServer wireMockServer;

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "sidecar.rate-limit.enabled", "true",
                    "sidecar.rate-limit.requests-per-second", "1",
                    "sidecar.rate-limit.burst-size", "2",
                    "sidecar.auth.enabled", "false",
                    "sidecar.authz.enabled", "false",
                    "sidecar.opa.enabled", "false",
                    "sidecar.proxy.target.port", "8091");
        }
    }

    @BeforeAll
    static void setup() {
        wireMockServer = new WireMockServer(8091);
        wireMockServer.start();
        WireMock.configureFor(8091);
        stubFor(get(urlMatching("/api/.*")).willReturn(aResponse().withStatus(200).withBody("OK")));
    }

    @AfterAll
    static void teardown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void testFilter_FirstRequest_Allowed() {
        String clientIp = "10.0.0.100";
        given()
                .header("X-Forwarded-For", clientIp)
                .when().get("/api/test-1")
                .then()
                .statusCode(200);
    }

    @Test
    void testFilter_BurstExceeded_Returns429() {
        String clientIp = "10.0.0.101";

        // Request 1: OK
        given()
                .header("X-Forwarded-For", clientIp)
                .when().get("/api/test-2-a")
                .then().statusCode(200);

        // Request 2: OK
        given()
                .header("X-Forwarded-For", clientIp)
                .when().get("/api/test-2-b")
                .then().statusCode(200);

        // Request 3: Rate Limited
        given()
                .header("X-Forwarded-For", clientIp)
                .when().get("/api/test-2-c")
                .then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                .body("code", is("too_many_requests"));
    }

    @Test
    void testFilter_InternalPath_Skipped() {
        given()
                .when().get("/q/health")
                .then()
                .statusCode(200);
    }
}
