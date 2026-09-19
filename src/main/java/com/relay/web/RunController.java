package com.relay.web;

import com.relay.run.RunService;
import com.relay.run.dto.RunDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Run inspection API. Returns the run with its full step trace (resolved inputs, outputs,
 * attempts, timing, token usage).
 */
@RestController
@RequestMapping("/runs")
public class RunController {

    private final RunService runs;

    public RunController(RunService runs) {
        this.runs = runs;
    }

    @GetMapping("/{id}")
    public RunDetail get(@PathVariable String id) {
        return runs.getDetail(id);
    }
}
