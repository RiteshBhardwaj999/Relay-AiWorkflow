package com.relay.engine.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.mockworld.MockWorldClient;
import org.springframework.stereotype.Component;

/**
 * Executes a sensitive commerce action on the mock world: {@code refund → /orders/{id}/refund},
 * {@code replacement → /orders/{id}/replacement}. Marked {@code requires_approval} in the catalog,
 * so the engine refuses to run it unless an approval was granted earlier in the run. A side effect,
 * so it carries the stable idempotency key.
 */
@Component
public class OrderActionExecutor implements NodeExecutor {

    private final MockWorldClient world;
    private final ObjectMapper mapper;

    public OrderActionExecutor(MockWorldClient world, ObjectMapper mapper) {
        this.world = world;
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "order_action";
    }

    @Override
    public ExecResult execute(NodeContext ctx) {
        String action = ctx.param("action");
        String orderId = ctx.param("order_id");
        if (action == null || orderId == null) {
            return ExecResult.fail("order_action requires 'action' and 'order_id'");
        }

        String path;
        ObjectNode payload = mapper.createObjectNode();
        if ("refund".equals(action)) {
            path = "/orders/" + orderId + "/refund";
            JsonNode amount = ctx.resolvedParams().get("amount_usd");
            if (amount != null && amount.isNumber()) {
                payload.set("amount_usd", amount);
            }
        } else if ("replacement".equals(action)) {
            path = "/orders/" + orderId + "/replacement";
        } else {
            return ExecResult.fail("unknown order_action '" + action + "'");
        }

        String key = ctx.idempotencyKey();
        MockWorldClient.Result result;
        try {
            result = world.post(path, payload, key);
        } catch (Exception e) {
            return ExecResult.failRetryable("order_action (" + action + ") error: " + e.getMessage());
        }
        if (!result.isSuccess()) {
            String msg = "order_action (" + action + ") failed: " + result.status() + " " + result.body();
            return result.status() >= 500 ? ExecResult.failRetryable(msg) : ExecResult.fail(msg);
        }
        return ExecResult.continueTo(result.body(), ctx.next()).withIdempotencyKey(key);
    }
}
