package com.relay.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure unit tests for {@link TemplateResolver} — no Spring or Docker. */
class TemplateResolverTest {

    final ObjectMapper mapper = new ObjectMapper();
    final TemplateResolver resolver = new TemplateResolver(mapper);

    private JsonNode root() throws Exception {
        JsonNode triggerBody = mapper.readTree("{\"amount_usd\":250,\"order_id\":\"ord_2003\"}");
        JsonNode classifyOut = mapper.readTree("{\"category\":\"refund_request\",\"priority\":\"high\"}");
        JsonNode shipOut = mapper.readTree("{\"body\":{\"shipment_id\":\"shp_123\"}}");
        return resolver.buildRoot(triggerBody, Map.of("classify", classifyOut, "create_shipment", shipOut));
    }

    @Test
    void resolvesTriggerAndNodePaths() throws Exception {
        JsonNode root = root();
        assertThat(resolver.resolveString("Amount: {{trigger.body.amount_usd}}", root)).isEqualTo("Amount: 250");
        assertThat(resolver.resolveString("{{nodes.classify.output.category}}", root)).isEqualTo("refund_request");
        assertThat(resolver.resolveString("Tracking {{nodes.create_shipment.output.body.shipment_id}}", root))
                .isEqualTo("Tracking shp_123");
    }

    @Test
    void deepResolvesObjectParams() throws Exception {
        JsonNode params = mapper.readTree("{\"url\":\"http://x/{{trigger.body.order_id}}\",\"body\":{\"id\":\"{{trigger.body.order_id}}\"}}");
        JsonNode resolved = resolver.resolveJson(params, root());
        assertThat(resolved.path("url").asText()).isEqualTo("http://x/ord_2003");
        assertThat(resolved.path("body").path("id").asText()).isEqualTo("ord_2003");
    }

    @Test
    void unresolvablePathThrows() throws Exception {
        JsonNode root = root();
        assertThatThrownBy(() -> resolver.resolveString("{{trigger.body.missing}}", root))
                .isInstanceOf(TemplateException.class)
                .hasMessageContaining("unresolvable template path");
    }
}
