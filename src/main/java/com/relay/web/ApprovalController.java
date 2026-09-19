package com.relay.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.run.ApprovalService;
import com.relay.run.ApprovalStatus;
import com.relay.run.dto.ApprovalView;
import com.relay.web.error.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Approval API: list pending approvals and record approve/reject decisions.
 */
@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @GetMapping
    public List<ApprovalView> list(@RequestParam(value = "status", required = false) String status) {
        return approvals.list(parseStatus(status));
    }

    @PostMapping("/{id}/approve")
    public ApprovalView approve(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
        return approvals.approve(id, decidedBy(body));
    }

    @PostMapping("/{id}/reject")
    public ApprovalView reject(@PathVariable String id, @RequestBody(required = false) JsonNode body) {
        return approvals.reject(id, decidedBy(body));
    }

    private ApprovalStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ApprovalStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Unknown approval status '" + status + "'");
        }
    }

    private String decidedBy(JsonNode body) {
        return (body != null && body.hasNonNull("decided_by")) ? body.get("decided_by").asText() : "console";
    }
}
