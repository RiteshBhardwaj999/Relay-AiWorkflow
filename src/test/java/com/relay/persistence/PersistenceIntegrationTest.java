package com.relay.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.relay.engine.queue.QueueJob;
import com.relay.engine.queue.QueueJobRepository;
import com.relay.run.Approval;
import com.relay.run.ApprovalRepository;
import com.relay.run.ApprovalStatus;
import com.relay.run.Run;
import com.relay.run.RunRepository;
import com.relay.run.RunStatus;
import com.relay.run.Step;
import com.relay.run.StepRepository;
import com.relay.run.StepStatus;
import com.relay.workflow.Workflow;
import com.relay.workflow.WorkflowRepository;
import com.relay.workflow.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the JPA mappings against a real Postgres (Testcontainers): JSONB columns round-trip,
 * timestamps persist, foreign keys hold, and the seed loader publishes the provided workflows.
 * This stands in for Hibernate DDL {@code validate} — Flyway owns the schema.
 */
@SpringBootTest
@Testcontainers
@EnabledIf("dockerAvailable")
class PersistenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    /** Gate the whole class: run only where Testcontainers can reach a Docker daemon. */
    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // keep the test off the developer's Redis; nothing here touches it
        registry.add("spring.data.redis.host", () -> "localhost");
    }

    @Autowired
    WorkflowRepository workflows;
    @Autowired
    RunRepository runs;
    @Autowired
    StepRepository steps;
    @Autowired
    ApprovalRepository approvals;
    @Autowired
    QueueJobRepository queueJobs;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void seedWorkflowsLoadedAndPublished() {
        List<String> expected = List.of(
                "wf_support_triage", "wf_expense_approval", "wf_slow_fulfillment", "wf_runaway");
        for (String id : expected) {
            Workflow wf = workflows.findById(id).orElse(null);
            assertThat(wf).as("seed workflow %s present", id).isNotNull();
            assertThat(wf.getStatus()).isEqualTo(WorkflowStatus.published);
            assertThat(wf.getDefinition().path("nodes")).isNotEmpty();
        }
    }

    @Test
    void runGraphRoundTripsThroughJsonb() throws Exception {
        // wf_expense_approval is seeded, so the FK on run.workflow_id is satisfiable
        var input = objectMapper.readTree("{\"amount_usd\":250,\"employee_email\":\"dev1@example.com\"}");
        var snapshot = workflows.findById("wf_expense_approval").orElseThrow().getDefinition();

        Run run = new Run("run_test_1", "wf_expense_approval", snapshot, "webhook", input);
        run.setStatus(RunStatus.running);
        run.setCurrentNodeId("is_large");
        runs.save(run);

        Step step = new Step("run_test_1", "is_large", "condition", 1, StepStatus.succeeded);
        step.setResolvedInput(objectMapper.readTree("{\"left\":\"250\",\"op\":\"greater_than\",\"right\":\"100\"}"));
        step.setOutput(objectMapper.readTree("{\"result\":true}"));
        step.setDurationMs(4);
        steps.save(step);

        Approval approval = new Approval("apr_test_1", "run_test_1", "finance_gate", "Expense of $250");
        approvals.save(approval);

        queueJobs.save(new QueueJob("run_test_1", Instant.now()));

        // reload and assert JSON + relationships survived the trip
        Run reloaded = runs.findById("run_test_1").orElseThrow();
        assertThat(reloaded.getInput().path("amount_usd").asInt()).isEqualTo(250);
        assertThat(reloaded.getDefinitionSnapshot().path("id").asText()).isEqualTo("wf_expense_approval");
        assertThat(reloaded.getStatus()).isEqualTo(RunStatus.running);

        List<Step> traceSteps = steps.findByRunIdOrderBySequenceAsc("run_test_1");
        assertThat(traceSteps).hasSize(1);
        assertThat(traceSteps.get(0).getOutput().path("result").asBoolean()).isTrue();

        assertThat(approvals.findByStatusOrderByCreatedAtAsc(ApprovalStatus.pending))
                .anyMatch(a -> a.getId().equals("apr_test_1"));
        assertThat(queueJobs.findByRunId("run_test_1")).hasSize(1);
    }
}
