package com.relay.catalog;

import java.util.List;

/**
 * Specification for a single node/trigger parameter, parsed from the node catalog.
 *
 * @param name        parameter name
 * @param type        declared type (string, object, number, ...)
 * @param required    whether the parameter must be present
 * @param templatable whether {@code {{...}}} templates are allowed in the value
 * @param enumValues  allowed values, or empty if unconstrained
 */
public record ParamSpec(
        String name,
        String type,
        boolean required,
        boolean templatable,
        List<String> enumValues) {
}
