package com.relay.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.relay.run.Run;
import com.relay.run.RunService;
import com.relay.run.dto.RunCreated;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Run triggers. Manual triggering is a platform API (Bearer, under {@code /workflows}); webhooks
 * are authenticated per-workflow with {@code X-Relay-Secret} and are deliberately outside the
 * bearer-protected paths.
 */
@RestController
public class TriggerController {

    private final RunService runs;

    public TriggerController(RunService runs) {
        this.runs = runs;
    }

    @PostMapping("/workflows/{id}/trigger")
    public ResponseEntity<RunCreated> triggerManual(@PathVariable String id,
                                                    @RequestBody(required = false) JsonNode body) {
        Run run = runs.triggerManual(id, body);
        return ResponseEntity.accepted().body(new RunCreated(run.getRunId()));
    }

    @PostMapping("/hooks/{id}")
    public ResponseEntity<RunCreated> triggerWebhook(@PathVariable String id,
                                                     @RequestHeader(value = "X-Relay-Secret", required = false) String secret,
                                                     @RequestBody(required = false) JsonNode body) {
        Run run = runs.triggerWebhook(id, secret, body);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new RunCreated(run.getRunId()));
    }
}
