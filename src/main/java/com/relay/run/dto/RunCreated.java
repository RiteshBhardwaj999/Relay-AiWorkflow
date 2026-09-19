package com.relay.run.dto;

/** Response to a trigger: the id of the newly enqueued run. */
public record RunCreated(String runId) {
}
