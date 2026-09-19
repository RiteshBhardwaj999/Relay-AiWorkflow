package com.relay.engine;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduled queue poller. The worker itself runs on its own thread (see {@link Worker}).
 */
@Configuration
@EnableScheduling
public class EngineConfig {
}
