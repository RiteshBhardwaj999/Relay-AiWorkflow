package com.relay.engine.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.ai.AiProvider;
import com.relay.ai.MockAiProvider;
import com.relay.run.Run;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the ai node: schema validation, one repair retry, token accounting. */
class AiExecutorTest {

    final ObjectMapper mapper = new ObjectMapper();

    private static final String SCHEMA = """
            {"type":"object",
             "properties":{"category":{"type":"string","enum":["refund_request","complaint","question"]}},
             "required":["category"],
             "additionalProperties":false}
            """;

    private NodeContext ctx() throws Exception {
        JsonNode node = mapper.readTree("{\"id\":\"classify\",\"type\":\"ai\",\"next\":\"route\"}");
        JsonNode params = mapper.readTree("{\"prompt\":\"classify: I want a refund\",\"output_schema\":" + SCHEMA + "}");
        Run run = new Run("run_1", "wf", mapper.createObjectNode(), "manual", mapper.createObjectNode());
        return new NodeContext(run, node, params, mapper.createObjectNode());
    }

    @Test
    void validOutputContinuesAndRecordsTokens() throws Exception {
        AiExecutor exec = new AiExecutor(new MockAiProvider(mapper), mapper);
        ExecResult r = exec.execute(ctx());
        assertThat(r.outcome()).isEqualTo(ExecResult.Outcome.CONTINUE);
        assertThat(r.nextNodeId()).isEqualTo("route");
        assertThat(r.output().path("category").asText()).isEqualTo("refund_request");
        assertThat(r.tokensPrompt()).isGreaterThan(0);
        assertThat(r.tokensCompletion()).isGreaterThan(0);
    }

    @Test
    void invalidThenValidRepairsOnce() throws Exception {
        // First call returns junk, second returns schema-valid JSON.
        AiProvider flaky = new AiProvider() {
            int calls = 0;
            @Override
            public Completion complete(String prompt, JsonNode outputSchema) {
                calls++;
                String content = calls == 1 ? "not json at all" : "{\"category\":\"complaint\"}";
                return new Completion(content, 5, 3);
            }
        };
        ExecResult r = new AiExecutor(flaky, mapper).execute(ctx());
        assertThat(r.outcome()).isEqualTo(ExecResult.Outcome.CONTINUE);
        assertThat(r.output().path("category").asText()).isEqualTo("complaint");
        assertThat(r.tokensPrompt()).isEqualTo(10); // two attempts × 5
    }

    @Test
    void failsAfterTwoInvalidResponses() throws Exception {
        AiProvider broken = (prompt, schema) -> new AiProvider.Completion("{\"category\":\"not_in_enum\"}", 4, 2);
        ExecResult r = new AiExecutor(broken, mapper).execute(ctx());
        assertThat(r.outcome()).isEqualTo(ExecResult.Outcome.FAIL);
        assertThat(r.errorMessage()).contains("after repair");
        assertThat(r.tokensPrompt()).isEqualTo(8); // tokens still recorded across both attempts
    }
}
