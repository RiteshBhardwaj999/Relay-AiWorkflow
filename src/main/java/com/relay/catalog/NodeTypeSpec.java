package com.relay.catalog;

import java.util.List;
import java.util.Map;

/**
 * A node type from the catalog: its params and the engine-relevant flags.
 *
 * @param type             node type name (e.g. {@code http_request})
 * @param sideEffect       true if the node mutates the outside world (must carry an idempotency key)
 * @param requiresApproval true if the node may only run after an approval was granted in the run
 * @param params           parameter specs keyed by name
 * @param branches         outgoing edge field names (e.g. {@code [on_true, on_false]}); empty ⇒ uses {@code next}
 */
public record NodeTypeSpec(
        String type,
        boolean sideEffect,
        boolean requiresApproval,
        Map<String, ParamSpec> params,
        List<String> branches) {

    public boolean isBranching() {
        return branches != null && !branches.isEmpty();
    }
}
