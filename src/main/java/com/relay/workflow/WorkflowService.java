package com.relay.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.web.error.ApiException;
import com.relay.workflow.dto.WorkflowDetail;
import com.relay.workflow.dto.WorkflowSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Workflow lifecycle: create drafts, list/get, and publish (freeze). Definitions are validated
 * against the node catalog on create and again on publish.
 */
@Service
public class WorkflowService {

    private final WorkflowRepository workflows;
    private final WorkflowValidator validator;

    public WorkflowService(WorkflowRepository workflows, WorkflowValidator validator) {
        this.workflows = workflows;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public List<WorkflowSummary> list() {
        return workflows.findAll().stream()
                .sorted(Comparator.comparing(Workflow::getId))
                .map(WorkflowSummary::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowDetail get(String id) {
        return WorkflowDetail.from(require(id));
    }

    @Transactional
    public WorkflowDetail create(JsonNode definition) {
        validator.validateOrThrow(definition);
        String id = definition.path("id").asText();
        if (workflows.existsById(id)) {
            throw ApiException.conflict("A workflow with id '" + id + "' already exists");
        }
        Workflow workflow = new Workflow(
                id,
                definition.path("name").asText(id),
                definition.path("description").asText(null),
                WorkflowStatus.draft,
                definition);
        return WorkflowDetail.from(workflows.save(workflow));
    }

    @Transactional
    public WorkflowDetail update(String id, JsonNode definition) {
        Workflow workflow = require(id);
        if (workflow.getStatus() == WorkflowStatus.published) {
            throw ApiException.conflict("Published workflow '" + id + "' is frozen and cannot be edited");
        }
        validator.validateOrThrow(definition);
        if (!definition.path("id").asText(id).equals(id)) {
            throw ApiException.badRequest("Definition id must match the workflow id in the path");
        }
        workflow.setName(definition.path("name").asText(id));
        workflow.setDescription(definition.path("description").asText(null));
        workflow.setDefinition(definition);
        workflow.touch();
        return WorkflowDetail.from(workflow);
    }

    @Transactional
    public WorkflowDetail publish(String id) {
        Workflow workflow = require(id);
        // Re-validate against the catalog before freezing; publishing is idempotent.
        validator.validateOrThrow(workflow.getDefinition());
        workflow.setStatus(WorkflowStatus.published);
        workflow.touch();
        return WorkflowDetail.from(workflow);
    }

    @Transactional(readOnly = true)
    public Workflow require(String id) {
        return workflows.findById(id)
                .orElseThrow(() -> ApiException.notFound("No workflow with id '" + id + "'"));
    }
}
