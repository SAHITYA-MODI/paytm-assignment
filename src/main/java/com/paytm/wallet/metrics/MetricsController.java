package com.paytm.wallet.metrics;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;


@RestController
public class MetricsController {

    private final PrometheusMeterRegistry registry;
    private final String dashboardHtml;

    public MetricsController(PrometheusMeterRegistry registry) {
        this.registry = registry;
        // Read once at startup rather than per request: it never changes, and a failure
        // to find it should surface as a startup error, not as a 500 on first view.
        try (InputStream in = new ClassPathResource("dashboard.html").getInputStream()) {
            this.dashboardHtml = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("dashboard.html missing from the classpath", e);
        }
    }

    @GetMapping(value = "/metrics", produces = MediaType.TEXT_PLAIN_VALUE)
    public String scrape() {
        return registry.scrape();
    }

    @GetMapping(value = "/dashboard", produces = MediaType.TEXT_HTML_VALUE)
    public String dashboard() {
        return dashboardHtml;
    }
}
