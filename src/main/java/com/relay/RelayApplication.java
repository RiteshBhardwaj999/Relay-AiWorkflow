package com.relay;

import com.relay.config.RelayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.TimeZone;

@SpringBootApplication
@EnableConfigurationProperties(RelayProperties.class)
public class RelayApplication {

    public static void main(String[] args) {
        // pgjdbc forwards the JVM default zone to the server; some builds reject legacy aliases
        // (e.g. "Asia/Calcutta"). Run everything in UTC for consistent, portable timestamps.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(RelayApplication.class, args);
    }
}
