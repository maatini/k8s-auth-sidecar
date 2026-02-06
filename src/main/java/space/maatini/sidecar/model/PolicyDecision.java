package space.maatini.sidecar.model;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Result of a policy evaluation from OPA.
 */
public record PolicyDecision(
        boolean allowed,
        String reason,
        List<String> violations,
        Map<String, Object> metadata) {
    /**
     * Creates an allowed decision.
     */
    public static PolicyDecision allow() {
        return new PolicyDecision(true, null, Collections.emptyList(), Collections.emptyMap());
    }

    /**
     * Creates an allowed decision with metadata.
     */
    public static PolicyDecision allow(Map<String, Object> metadata) {
        return new PolicyDecision(true, null, Collections.emptyList(), metadata);
    }

    /**
     * Creates a denied decision with a reason.
     */
    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(false, reason, Collections.emptyList(), Collections.emptyMap());
    }

    /**
     * Creates a denied decision with violations.
     */
    public static PolicyDecision deny(String reason, List<String> violations) {
        return new PolicyDecision(false, reason, violations != null ? List.copyOf(violations) : Collections.emptyList(),
                Collections.emptyMap());
    }

    /**
     * Creates a denied decision with detailed information.
     */
    public static PolicyDecision deny(String reason, List<String> violations, Map<String, Object> metadata) {
        return new PolicyDecision(
                false,
                reason,
                violations != null ? List.copyOf(violations) : Collections.emptyList(),
                metadata != null ? Map.copyOf(metadata) : Collections.emptyMap());
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
        private List<String> violations = Collections.emptyList();
        private Map<String, Object> metadata = Collections.emptyMap();

        public Builder allowed(boolean allowed) {
            this.allowed = allowed;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public Builder violations(List<String> violations) {
            this.violations = violations != null ? List.copyOf(violations) : Collections.emptyList();
            return this;
        }

        public Builder addViolation(String violation) {
            if (this.violations.isEmpty()) {
                this.violations = new java.util.ArrayList<>();
            }
            if (this.violations instanceof java.util.ArrayList) {
                this.violations.add(violation);
            }
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata != null ? Map.copyOf(metadata) : Collections.emptyMap();
            return this;
        }

        public PolicyDecision build() {
            return new PolicyDecision(allowed, reason, violations, metadata);
        }
    }
}
