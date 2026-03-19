package de.edeka.eit.sidecar.domain.model;
 
import io.quarkus.runtime.annotations.RegisterForReflection;
 
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
 
/**
 * Result of a policy evaluation from OPA.
 */
@RegisterForReflection(registerFullHierarchy = true)
public record PolicyDecision(
        boolean allowed,
        String reason,
        List<String> violations,
        Map<String, Object> metadata,
        Set<String> permissions) {
    /**
     * Creates an allowed decision.
     */
    public static PolicyDecision allow() {
        return new PolicyDecision(true, null, Collections.emptyList(), Collections.emptyMap(), Collections.emptySet());
    }
 
    /**
     * Creates an allowed decision with metadata.
     */
    public static PolicyDecision allow(Map<String, Object> metadata) {
        return new PolicyDecision(true, null, Collections.emptyList(), metadata, Collections.emptySet());
    }
 
    /**
     * Creates a denied decision with a reason.
     */
    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(false, reason, Collections.emptyList(), Collections.emptyMap(), Collections.emptySet());
    }
 
    /**
     * Creates a denied decision with violations.
     */
    public static PolicyDecision deny(String reason, List<String> violations) {
        return new PolicyDecision(false, reason, violations != null ? List.copyOf(violations) : Collections.emptyList(),
                Collections.emptyMap(), Collections.emptySet());
    }
 
    /**
     * Creates a denied decision with detailed information.
     */
    public static PolicyDecision deny(String reason, List<String> violations, Map<String, Object> metadata) {
        return new PolicyDecision(
                false,
                reason,
                violations != null ? List.copyOf(violations) : Collections.emptyList(),
                metadata != null ? Map.copyOf(metadata) : Collections.emptyMap(),
                Collections.emptySet());
    }
 
    /**
     * Builder for creating PolicyDecision instances.
     */
    public static Builder builder() {
        return new Builder();
    }
 
    /**
     * Returns the first violation if any.
     */
    public Optional<String> firstViolation() {
        return violations != null && !violations.isEmpty()
                ? Optional.of(violations.get(0))
                : Optional.empty();
    }
 
    /**
     * Returns a metadata value by key.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getMetadata(String key) {
        return metadata != null
                ? Optional.ofNullable((T) metadata.get(key))
                : Optional.empty();
    }
 
    /**
     * Builder for PolicyDecision.
     */
    public static class Builder {
        private boolean allowed = false;
        private String reason;
        private List<String> violations = new java.util.ArrayList<>();
        private Map<String, Object> metadata = Collections.emptyMap();
        private Set<String> permissions = Collections.emptySet();
 
        public Builder allowed(boolean allowed) {
            this.allowed = allowed;
            return this;
        }
 
        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }
 
        public Builder violations(List<String> violations) {
            this.violations = violations != null ? new java.util.ArrayList<>(violations) : new java.util.ArrayList<>();
            return this;
        }
 
        public Builder addViolation(String violation) {
            this.violations.add(violation);
            return this;
        }
 
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata != null ? Map.copyOf(metadata) : Collections.emptyMap();
            return this;
        }

        public Builder permissions(Set<String> permissions) {
            this.permissions = permissions != null ? Set.copyOf(permissions) : Collections.emptySet();
            return this;
        }
 
        public PolicyDecision build() {
            return new PolicyDecision(allowed, reason, violations, metadata, permissions);
        }
    }
}
