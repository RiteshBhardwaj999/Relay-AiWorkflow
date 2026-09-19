package com.relay.engine.nodes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/**
 * Evaluates {@code left <op> right} and routes to {@code on_true}/{@code on_false}. The only node
 * type with two outgoing edges. Numeric operators compare numerically when both sides parse as
 * numbers, otherwise the step fails with a clear message.
 */
@Component
public class ConditionExecutor implements NodeExecutor {

    private final ObjectMapper mapper;

    public ConditionExecutor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String type() {
        return "condition";
    }

    @Override
    public ExecResult execute(NodeContext ctx) {
        String left = ctx.param("left");
        String op = ctx.param("op");
        String right = ctx.param("right");
        if (left == null || op == null || right == null) {
            return ExecResult.fail("condition requires left, op and right");
        }

        boolean result;
        switch (op) {
            case "equals" -> result = left.equals(right);
            case "not_equals" -> result = !left.equals(right);
            case "contains" -> result = left.contains(right);
            case "greater_than", "less_than" -> {
                Double l = parseNumber(left);
                Double r = parseNumber(right);
                if (l == null || r == null) {
                    return ExecResult.fail("condition '" + op + "' needs numeric operands but got '"
                            + left + "' and '" + right + "'");
                }
                result = op.equals("greater_than") ? l > r : l < r;
            }
            default -> {
                return ExecResult.fail("unknown condition operator '" + op + "'");
            }
        }

        String nextNodeId = result
                ? ctx.node().path("on_true").asText(null)
                : ctx.node().path("on_false").asText(null);
        ObjectNode output = mapper.createObjectNode();
        output.put("result", result);
        return ExecResult.continueTo(output, nextNodeId);
    }

    private Double parseNumber(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
