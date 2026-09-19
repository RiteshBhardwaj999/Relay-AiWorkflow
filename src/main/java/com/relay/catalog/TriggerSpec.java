package com.relay.catalog;

import java.util.Map;

/**
 * A trigger type from the catalog (webhook, schedule, manual) and its config params.
 */
public record TriggerSpec(String type, Map<String, ParamSpec> config) {
}
