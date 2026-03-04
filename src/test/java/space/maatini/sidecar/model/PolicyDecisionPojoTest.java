package space.maatini.sidecar.model;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PolicyDecisionPojoTest {

    @Test
    void testAllow() {
        PolicyDecision decision = PolicyDecision.allow();
        assertTrue(decision.allowed());
        assertNull(decision.reason());
        assertTrue(decision.violations().isEmpty());
        assertTrue(decision.metadata().isEmpty());
    }

    @Test
    void testDenyWithReason() {
        PolicyDecision decision = PolicyDecision.deny("Not allowed");
        assertFalse(decision.allowed());
        assertEquals("Not allowed", decision.reason());
        assertTrue(decision.violations().isEmpty());
        assertTrue(decision.metadata().isEmpty());
    }

    @Test
    void testBuilderFull() {
        PolicyDecision decision = PolicyDecision.builder()
                .allowed(false)
                .reason("Test Reason")
                .violations(List.of("Violation 1", "Violation 2"))
                .metadata(Map.of("key", "value"))
                .build();
        
        assertFalse(decision.allowed());
        assertEquals("Test Reason", decision.reason());
        assertEquals(2, decision.violations().size());
        assertEquals("value", decision.metadata().get("key"));
    }

    @Test
    void testBuilderNullCollections() {
        PolicyDecision decision = PolicyDecision.builder()
                .allowed(true)
                .violations(null)
                .metadata(null)
                .build();
        
        assertTrue(decision.allowed());
        assertNotNull(decision.violations());
        assertTrue(decision.violations().isEmpty());
        assertNotNull(decision.metadata());
        assertTrue(decision.metadata().isEmpty());
    }
    
    @Test
    void testBuilderAddViolation() {
        PolicyDecision decision = PolicyDecision.builder()
                .allowed(false)
                .addViolation("V1")
                .addViolation("V2")
                .build();
        
        assertEquals(2, decision.violations().size());
        assertEquals("V1", decision.violations().get(0));
    }
}
