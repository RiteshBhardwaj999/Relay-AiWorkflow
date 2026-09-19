package com.relay.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.workflow.WorkflowService;
import com.relay.workflow.dto.WorkflowDetail;
import com.relay.workflow.dto.WorkflowSummary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Workflow management API: list, create (draft), fetch, update (draft only), and publish.
 * Triggering lives in {@code TriggerController} (Phase 3).
 */
@RestController
@RequestMapping("/workflows")
public class WorkflowController {

    private final WorkflowService workflows;

    public WorkflowController(WorkflowService workflows) {
        this.workflows = workflows;
    }

    @GetMapping
    public List<WorkflowSummary> list() {
        return workflows.list();
    }

    @GetMapping("/{id}")
    public WorkflowDetail get(@PathVariable String id) {
        return workflows.get(id);
    }

    @PostMapping
    public ResponseEntity<WorkflowDetail> create(@RequestBody JsonNode definition) {
        return ResponseEntity.status(HttpStatus.CREATED).body(workflows.create(definition));
    }

    @PutMapping("/{id}")
    public WorkflowDetail update(@PathVariable String id, @RequestBody JsonNode definition) {
        return workflows.update(id, definition);
    }

    @PostMapping("/{id}/publish")
    public WorkflowDetail publish(@PathVariable String id) {
        return workflows.publish(id);
    }
}
