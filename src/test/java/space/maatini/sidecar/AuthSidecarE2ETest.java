package space.maatini.sidecar;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.jwt.Claim;
import io.quarkus.test.security.jwt.JwtSecurity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;

/**
 * End-to-End integration test for the sidecar proxy pipeline.
 */
@QuarkusTest
@TestProfile(AuthSidecarE2ETest.E2EProfile.class)
public class AuthSidecarE2ETest {

        static WireMockServer wireMockServer;

        public static class E2EProfile implements QuarkusTestProfile {
                @Override
                public Map<String, String> getConfigOverrides() {
                        return Map.of(
                                        "sidecar.proxy.target.port", "8089",
                                        "sidecar.auth.enabled", "false",
                                        "sidecar.authz.enabled", "false",
                                        "sidecar.opa.enabled", "false");
                }
        }

        @BeforeAll
        static void setup() {
                wireMockServer = new WireMockServer(8089);
                wireMockServer.start();
                WireMock.configureFor(8089);

                stubFor(get(urlEqualTo("/api/users/user123/profile"))
                                .willReturn(aResponse()
                                                .withStatus(200)
                                                .withHeader("Content-Type", "application/json")
                                                .withBody("{\"id\":\"user123\",\"status\":\"active\"}")));

                stubFor(get(urlEqualTo("/api/admin/error"))
                                .willReturn(aResponse()
                                                .withStatus(500)));
        }

        @AfterAll
        static void teardown() {
                if (wireMockServer != null) {
                        wireMockServer.stop();
                }
        }

        @Test
        @TestSecurity(user = "user123", roles = { "user" })
        @JwtSecurity(claims = {
                        @Claim(key = "sub", value = "user123"),
                        @Claim(key = "email", value = "user123@example.com")
        })
        void testHappyPath_OwnProfile_ShouldReturn200() {
                given()
                                .header("X-Forwarded-For", "client-happy-path")
                                .when().get("/api/users/user123/profile")
                                .then()
                                .statusCode(200)
                                .body("id", is("user123"));
        }

        @Test
        @TestSecurity(user = "admin1", roles = { "admin" })
        @JwtSecurity(claims = { @Claim(key = "sub", value = "admin1") })
        void testErrorPath_ShouldReturn500() {
                given()
                                .header("X-Forwarded-For", "client-resilience")
                                .when().get("/api/admin/error")
                                .then()
                                .statusCode(500);
        }
}
