package de.edeka.eit.sidecar.domain.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.Set;

/**
 * Stable cache key for policy decisions.
 * Reduces cardinality by excluding high-cardinality fields like unique request headers or timestamps.
 */
@RegisterForReflection
public record PolicyCacheKey(
        String userId,
        Set<String> roles,
        Set<String> permissions,
        String method,
        String path) {
}
