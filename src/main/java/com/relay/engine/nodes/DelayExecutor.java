package com.relay.engine.nodes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Pauses the run for {@code seconds}, durably. Rather than blocking a worker thread, it advances
 * past itself and reschedules the run to become runnable at {@code resume_at} (persisted with the
 * step and the queue job in one transaction). A crash during the wait simply leaves a future-dated
 * queue job that the poller picks up when due.
 */
@Component
public class DelayExecutor implements NodeExecutor {

    private final ObjectMapper mapper;

    public DelayExecutor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "delay";
    }

    @Override
    public ExecResult execute(NodeContext ctx) {
        double seconds = ctx.resolvedParams().path("seconds").asDouble(0);
        if (seconds < 0) {
            return ExecResult.fail("delay seconds must be >= 0");
        }
        Instant resumeAt = Instant.now().plusMillis((long) (seconds * 1000));
        ObjectNode output = mapper.createObjectNode();
        output.put("seconds", seconds);
        output.put("resume_at", resumeAt.toString());
        return ExecResult.sleepUntil(output, ctx.next(), resumeAt);
    }
}
