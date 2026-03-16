package de.edeka.eit.sidecar.application.service;
import de.edeka.eit.sidecar.infrastructure.policy.WasmPolicyEngine;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import de.edeka.eit.sidecar.domain.model.AuthContext;
import de.edeka.eit.sidecar.domain.model.PolicyDecision;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for PolicyService using the actual WASM policy.
 */
@QuarkusTest
@TestProfile(PolicyServiceRegoTest.PolicyTestProfile.class)
public class PolicyServiceRegoTest {

        public static class PolicyTestProfile implements QuarkusTestProfile {
                @Override
                public Map<String, String> getConfigOverrides() {
                        return Map.of(
                                        "sidecar.opa.enabled", "true",
                                        "sidecar.opa.embedded.wasm-path", "classpath:policies/policy.wasm");
                }
        }

        @Inject
        PolicyService policyService;

        @Test
        void testSuperadminCanAccessAnything() {
                AuthContext context = AuthContext.builder()
                                .userId("u1")
                                .roles(Set.of("superadmin"))
                                .build();

                PolicyDecision decision = policyService
                                .evaluate(context, "DELETE", "/api/super-secret", Map.of(), Map.of())
                                .await().indefinitely();

                assertTrue(decision.allowed());
        }

        @Test
        void testAdminPathsRequireAdminRole() {
                AuthContext adminContext = AuthContext.builder()
                                .userId("u2")
                                .roles(Set.of("admin"))
                                .build();

                PolicyDecision adminDecision = policyService
                                .evaluate(adminContext, "GET", "/api/admin/settings", Map.of(), Map.of())
                                .await().indefinitely();
                assertTrue(adminDecision.allowed());

                AuthContext userContext = AuthContext.builder()
                                .userId("u3")
                                .roles(Set.of("user"))
                                .build();

                PolicyDecision userDecision = policyService
                                .evaluate(userContext, "GET", "/api/admin/settings", Map.of(), Map.of())
                                .await().indefinitely();
                assertFalse(userDecision.allowed());
        }

        @Test
        void testPublicPathsFreeForAll() {
                AuthContext anonymousContext = AuthContext.anonymous();

                PolicyDecision decision = policyService
                                .evaluate(anonymousContext, "GET", "/api/public/info", Map.of(), Map.of())
                                .await().indefinitely();

                assertTrue(decision.allowed());
        }
}
