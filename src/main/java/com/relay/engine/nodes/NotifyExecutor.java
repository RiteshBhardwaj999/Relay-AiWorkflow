package com.relay.engine.nodes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.mockworld.MockWorldClient;
import org.springframework.stereotype.Component;

/**
 * Sends a notification via the mock world: {@code email → /email/send}, {@code chat → /chat/message}.
 * A side effect, so it always carries the stable idempotency key {@code {run_id}:{node_id}}.
 */
@Component
public class NotifyExecutor implements NodeExecutor {

    private final MockWorldClient world;
    private final ObjectMapper mapper;

    public NotifyExecutor(MockWorldClient world, ObjectMapper mapper) {
        this.world = world;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "notify";
    }

    @Override
    public ExecResult execute(NodeContext ctx) {
        String channel = ctx.param("channel");
        String to = ctx.param("to");
        String message = ctx.param("message");
        String subject = ctx.param("subject");
        if (message == null || to == null) {
            return ExecResult.fail("notify requires 'to' and 'message'");
        }

        String path;
        ObjectNode payload = mapper.createObjectNode();
        if ("email".equals(channel)) {
            path = "/email/send";
            payload.put("to", to);
            payload.put("message", message);
            if (subject != null) {
                payload.put("subject", subject);
            }
        } else if ("chat".equals(channel)) {
            path = "/chat/message";
            payload.put("channel", to);
            payload.put("message", message);
        } else {
            return ExecResult.fail("unknown notify channel '" + channel + "'");
        }

        String key = ctx.idempotencyKey();
        MockWorldClient.Result result;
        try {
            result = world.post(path, payload, key);
        } catch (Exception e) {
            return ExecResult.failRetryable("notify (" + channel + ") error: " + e.getMessage());
        }
        if (!result.isSuccess()) {
            String msg = "notify (" + channel + ") failed: " + result.status() + " " + result.body();
            return result.status() >= 500 ? ExecResult.failRetryable(msg) : ExecResult.fail(msg);
        }
        return ExecResult.continueTo(result.body(), ctx.next()).withIdempotencyKey(key);
    }
}
