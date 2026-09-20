package com.relay.web;

import com.relay.run.ApprovalService;
import com.relay.run.ApprovalStatus;
import com.relay.run.RunService;
import com.relay.workflow.WorkflowService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Server-rendered console (Thymeleaf). Read-only views over workflows, run history, and traces,
 * plus approve/reject actions. Served under {@code /console} — outside the bearer-protected API
 * paths — and calls the services directly, so the browser needs no API token.
 */
@Controller
public class ConsoleController {

    private final WorkflowService workflows;
    private final RunService runs;
    private final ApprovalService approvals;

    public ConsoleController(WorkflowService workflows, RunService runs, ApprovalService approvals) {
        this.workflows = workflows;
        this.runs = runs;
        this.approvals = approvals;
    }

    @GetMapping("/console")
    public String home() {
        return "redirect:/console/workflows";
    }

    @GetMapping("/console/workflows")
    public String workflows(Model model) {
        model.addAttribute("active", "workflows");
        model.addAttribute("workflows", workflows.list());
        return "workflows";
    }

    @GetMapping("/console/runs")
    public String runs(Model model) {
        model.addAttribute("active", "runs");
        model.addAttribute("runs", runs.recent());
        return "runs";
    }

    @GetMapping("/console/runs/{id}")
    public String run(@PathVariable String id, Model model) {
        model.addAttribute("active", "runs");
        model.addAttribute("run", runs.getDetail(id));
        return "run";
    }

    @GetMapping("/console/approvals")
    public String approvals(@RequestParam(value = "status", required = false, defaultValue = "pending") String status,
                            Model model) {
        model.addAttribute("active", "approvals");
        model.addAttribute("approvals", approvals.list(ApprovalStatus.valueOf(status)));
        return "approvals";
    }

    @PostMapping("/console/approvals/{id}/approve")
    public String approve(@PathVariable String id) {
        approvals.approve(id, "console");
        return "redirect:/console/approvals";
    }

    @PostMapping("/console/approvals/{id}/reject")
    public String reject(@PathVariable String id) {
        approvals.reject(id, "console");
        return "redirect:/console/approvals";
    }
}
