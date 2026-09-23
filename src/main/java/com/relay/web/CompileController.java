package com.relay.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.relay.nl.CompileResult;
import com.relay.nl.NlCompilerService;
import com.relay.nl.NlEvalService;
import com.relay.web.error.ApiException;
import com.relay.workflow.WorkflowService;
import com.relay.workflow.dto.WorkflowDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Good-To-Have natural-language compiler API. {@code POST /workflows/compile} turns a description
 * into a workflow (or a refusal); {@code POST /workflows/compile/eval} runs the labelled eval set
 * and reports accuracy. Bearer-protected like the rest of {@code /workflows}.
 */
@RestController
public class CompileController {

    private final NlCompilerService compiler;
    private final NlEvalService eval;
    private final WorkflowService workflows;
    private final ObjectMapper mapper;

    public CompileController(NlCompilerService compiler, NlEvalService eval, WorkflowService workflows,
                             ObjectMapper mapper) {
        this.compiler = compiler;
        this.eval = eval;
        this.workflows = workflows;
        this.mapper = mapper;
    }

    @PostMapping("/workflows/compile")
    public ResponseEntity<ObjectNode> compile(@RequestBody JsonNode body,
                                              @RequestParam(value = "save", defaultValue = "false") boolean save) {
        String description = body.path("description").asText(null);
        if (description == null || description.isBlank()) {
            throw ApiException.badRequest("Request body must include a 'description'");
        }
        CompileResult result = compiler.compile(description);

        ObjectNode response = mapper.createObjectNode();
        response.put("attempts", result.attempts());
        response.put("prompt_tokens", result.promptTokens());
        response.put("completion_tokens", result.completionTokens());

        if (result.refused()) {
            response.put("status", "refused");
            response.put("reason", result.refusalReason());
            response.set("missing_capabilities", mapper.valueToTree(result.missingCapabilities()));
            return ResponseEntity.ok(response);
        }
        if (!result.compiled()) {
            response.put("status", "error");
            response.set("validation_errors", mapper.valueToTree(result.validationErrors()));
            return ResponseEntity.unprocessableEntity().body(response);
        }

        response.put("status", "compiled");
        response.set("workflow", result.workflow());
        if (save) {
            WorkflowDetail draft = saveAsDraft(result.workflow());
            response.put("draft_id", draft.id());
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/workflows/compile/eval")
    public ObjectNode eval() {
        return eval.runEval();
    }

    /** Persist the compiled definition as a draft, giving it a fresh id if one already exists. */
    private WorkflowDetail saveAsDraft(JsonNode workflow) {
        ObjectNode def = workflow.deepCopy();
        try {
            return workflows.create(def);
        } catch (ApiException conflict) {
            def.put("id", def.path("id").asText("wf") + "_" + Long.toString(System.nanoTime(), 36));
            return workflows.create(def);
        }
    }
}
