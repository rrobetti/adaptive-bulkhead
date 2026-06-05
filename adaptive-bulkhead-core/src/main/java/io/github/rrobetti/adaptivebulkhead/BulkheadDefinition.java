package io.github.rrobetti.adaptivebulkhead;

import java.util.List;
import java.util.Objects;

public record BulkheadDefinition(
        String name,
        BulkheadPolicy policy,
        List<BulkheadDefinition> children
) {
    public BulkheadDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        Objects.requireNonNull(policy, "policy");
        children = List.copyOf(Objects.requireNonNull(children, "children"));
    }
}
