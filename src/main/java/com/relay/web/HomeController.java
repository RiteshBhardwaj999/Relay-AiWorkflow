package com.relay.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Minimal landing endpoint so the root path returns something useful. The real console
 * (Thymeleaf) arrives in Phase 9; actuator exposes {@code /actuator/health}.
 */
@RestController
public class HomeController {

    @GetMapping("/")
    public Map<String, Object> home() {
        return Map.of(
                "service", "relay",
                "status", "up",
                "console", "/console (coming in phase 9)",
                "health", "/actuator/health");
    }
}
