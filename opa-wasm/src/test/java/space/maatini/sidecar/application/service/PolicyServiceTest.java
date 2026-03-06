package space.maatini.sidecar.application.service;
import space.maatini.sidecar.infrastructure.policy.WasmPolicyEngine;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import space.maatini.sidecar.infrastructure.config.SidecarConfig;
import space.maatini.sidecar.domain.model.AuthContext;
import space.maatini.sidecar.domain.model.PolicyDecision;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@io.quarkus.test.junit.QuarkusTest
class PolicyServiceTest {

        PolicyService policyService;
        SidecarConfig config;
        WasmPolicyEngine wasmEngine;

        @BeforeEach
        void setup() throws Exception {
                policyService = new PolicyService();
                config = mock(SidecarConfig.class);
                wasmEngine = mock(WasmPolicyEngine.class);

                setField(policyService, "config", config);
                setField(policyService, "wasmEngine", wasmEngine);
                setField(policyService, "objectMapper", new ObjectMapper());
        }

        private void setField(Object target, String name, Object value) throws Exception {
                java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
        }

        @Test
        void testEvaluate_Disabled() {
                SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
                when(config.opa()).thenReturn(opa);
                when(opa.enabled()).thenReturn(false);

                PolicyDecision res = policyService.evaluate(AuthContext.anonymous(), "GET", "/", null, null).await()
                                .indefinitely();
                assertTrue(res.allowed());
        }

        @Test
        void testEvaluate_Embedded_Success() {
                SidecarConfig.OpaConfig opa = mock(SidecarConfig.OpaConfig.class);
                when(config.opa()).thenReturn(opa);
                when(opa.enabled()).thenReturn(true);

                when(wasmEngine.evaluateEmbeddedWasm(any())).thenReturn(Uni.createFrom().item(PolicyDecision.allow()));

                PolicyDecision res = policyService.evaluate(AuthContext.anonymous(), "GET", "/", null, null).await()
                                .indefinitely();
                assertTrue(res.allowed());
                verify(wasmEngine).evaluateEmbeddedWasm(any());
        }
}
