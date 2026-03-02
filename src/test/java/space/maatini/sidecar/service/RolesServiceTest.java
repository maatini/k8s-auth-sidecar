package space.maatini.sidecar.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.model.AuthContext;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.BeforeEach;
import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;

import java.util.Map;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestProfile(RolesServiceTest.Profile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RolesServiceTest {

    static WireMockServer wireMockServer;

    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "sidecar.authz.roles-service.enabled", "true",
                    "quarkus.oidc.tenant-enabled", "true",
                    "quarkus.fault-tolerance.enabled", "true",
                    "quarkus.rest-client.\"space.maatini.sidecar.client.RolesServiceClient\".url",
                    "http://localhost:8092",
                    // Faster thresholds for testing
                    "auth.roles-service.fault-tolerance.retry.max-retries", "2",
                    "auth.roles-service.fault-tolerance.retry.delay", "50",
                    "auth.roles-service.fault-tolerance.circuit-breaker.request-volume-threshold", "2",
                    "auth.roles-service.fault-tolerance.circuit-breaker.delay", "5000",
                    "auth.roles-service.fault-tolerance.timeout", "200");
        }
    }

    @BeforeAll
    static void setup() {
        wireMockServer = new WireMockServer(8092);
        wireMockServer.start();
        WireMock.configureFor(8092);
    }

    @AfterAll
    static void teardown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void resetWiremock() {
        wireMockServer.resetAll();
    }

    @Inject
    RolesService rolesService;

    @Inject
    CircuitBreakerMaintenance cbMaintenance;

    @BeforeEach
    void resetCircuitBreaker() {
        cbMaintenance.resetAll();
    }

    @Test
    @Order(1)
    void testEnrichWithRoles_Success() {
        stubFor(get(urlEqualTo("/api/v1/users/user123/roles"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                "{\"userId\":\"user123\",\"roles\":[\"admin\"],\"permissions\":[\"read:all\"],\"tenant\":\"tenant-1\"}")));

        AuthContext initialContext = AuthContext.builder()
                .userId("user123")
                .roles(Set.of("user"))
                .build();

        AuthContext enriched = rolesService.enrichWithRoles(initialContext).await().indefinitely();

        assertNotNull(enriched);
        assertEquals("user123", enriched.userId());
        assertTrue(enriched.roles().contains("user"));
        assertTrue(enriched.roles().contains("admin"));
        assertTrue(enriched.permissions().contains("read:all"));
    }

    @Test
    @Order(2)
    void testEnrichWithRoles_RetrySuccess() {
        // WireMock Scenario for 2 errors, then success (3rd attempt works)
        stubFor(get(urlEqualTo("/api/v1/users/retry-user/roles"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("Attempt 2"));

        stubFor(get(urlEqualTo("/api/v1/users/retry-user/roles"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("Attempt 2")
                .willReturn(aResponse().withStatus(500))
                .willSetStateTo("Attempt 3"));

        stubFor(get(urlEqualTo("/api/v1/users/retry-user/roles"))
                .inScenario("Retry Scenario")
                .whenScenarioStateIs("Attempt 3")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"userId\":\"retry-user\",\"roles\":[\"retry-success\"]}")));

        AuthContext initialContext = AuthContext.builder().userId("retry-user").build();
        AuthContext result = rolesService.enrichWithRoles(initialContext).await().indefinitely();

        System.out.println("RETRY TEST ROLES: " + result.roles());

        assertTrue(result.roles().contains("retry-success"));

        // Verify it was actually called 3 times
        verify(3, getRequestedFor(urlEqualTo("/api/v1/users/retry-user/roles")));
    }

    @Test
    @Order(3)
    void testEnrichWithRoles_TimeoutTriggersFallback() {
        // Slow response > 200ms timeout
        stubFor(get(urlEqualTo("/api/v1/users/slow-user/roles"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withFixedDelay(500)
                        .withBody("{\"userId\":\"slow-user\",\"roles\":[\"admin\"]}")));

        AuthContext initialContext = AuthContext.builder().userId("slow-user").build();
        AuthContext result = rolesService.enrichWithRoles(initialContext).await().indefinitely();

        // Expect fallback roles, NOT "admin"
        assertTrue(result.roles().contains("offline-user"));
        assertFalse(result.roles().contains("admin"));
    }

    @Test
    @Order(5)
    void testEnrichWithRoles_CircuitBreakerOpens() {
        // Setup a strict failure for cb-user
        stubFor(get(urlMatching("/api/v1/users/cb-fail-.*/roles"))
                .willReturn(aResponse().withStatus(503)));

        stubFor(get(urlEqualTo("/api/v1/users/cb-good/roles"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"userId\":\"cb-good\",\"roles\":[\"good-role\"]}")));

        // 1st request -> Fails 3 times (1 initial + 2 retries)
        AuthContext fail1 = AuthContext.builder().userId("cb-fail-1").build();
        rolesService.enrichWithRoles(fail1).await().indefinitely();

        // 2nd request -> Fails 3 times -> Threshold hit -> Circuit Breaker OPENS
        AuthContext fail2 = AuthContext.builder().userId("cb-fail-2").build();
        rolesService.enrichWithRoles(fail2).await().indefinitely();

        // 3rd request to a GOOD user. But CB is OPEN, so it should immediate fail
        // (fallback)
        // without hitting WireMock!
        AuthContext good = AuthContext.builder().userId("cb-good").build();
        AuthContext result = rolesService.enrichWithRoles(good).await().indefinitely();

        // Expect fallback roles due to OPEN circuit!
        assertTrue(result.roles().contains("offline-user"));
        assertFalse(result.roles().contains("good-role"));

        // Verify the GOOD endpoint was NEVER called because CB blocked it
        verify(0, getRequestedFor(urlEqualTo("/api/v1/users/cb-good/roles")));
    }

    @Test
    @Order(4)
    void testEnrichWithRoles_EmptyResponse() {
        // Returns a technically valid JSON with no roles
        stubFor(get(urlEqualTo("/api/v1/users/empty-user/roles"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"userId\":\"empty-user\",\"roles\":[],\"permissions\":[]}")));

        AuthContext initialContext = AuthContext.builder().userId("empty-user").build();
        AuthContext result = rolesService.enrichWithRoles(initialContext).await().indefinitely();

        // Context should still be enriched and safe
        assertEquals("empty-user", result.userId());
        assertTrue(result.roles().isEmpty());
    }

    @Test
    @Order(6)
    void testEnrichWithRoles_NullContext() {
        AuthContext result = rolesService.enrichWithRoles(null).await().indefinitely();
        assertNotNull(result);
        assertEquals("anonymous", result.userId());
        assertFalse(result.isAuthenticated());
    }
}
