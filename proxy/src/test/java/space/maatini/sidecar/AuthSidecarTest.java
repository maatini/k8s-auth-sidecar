package space.maatini.sidecar;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;

/**
 * Integration tests for the AuthN/AuthZ sidecar.
 */
@QuarkusTest
class AuthSidecarTest {

    @Test
    void testHealthLiveEndpoint() {
        given()
            .when().get("/q/health/live")
            .then()
            .statusCode(200)
            .body("status", equalTo("UP"));
    }

    @Test
    void testHealthReadyEndpoint() {
        given()
            .when().get("/q/health/ready")
            .then()
            .statusCode(anyOf(equalTo(200), equalTo(503))); // May fail if backend not available
    }

    @Test
    void testMetricsEndpoint() {
        given()
            .when().get("/q/metrics")
            .then()
            .statusCode(200);
    }

    @Test
    void testUnauthenticatedApiRequestReturns401() {
        given()
            .when().get("/api/users")
            .then()
            .statusCode(401);
    }

    @Test
    void testPublicPathAllowed() {
        // Public paths should not require authentication
        given()
            .when().get("/q/health")
            .then()
            .statusCode(200);
    }
}
