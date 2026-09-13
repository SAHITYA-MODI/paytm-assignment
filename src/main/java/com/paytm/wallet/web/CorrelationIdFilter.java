package com.paytm.wallet.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Gives every request a correlation id — reused from the client's X-Correlation-Id
 * header if it sent one, otherwise generated here — and puts it in SLF4J's MDC
 * (learning.md #14) so every log line written while handling this request carries it,
 * without every log call having to pass it explicitly. Echoed back as a response header
 * so a caller can find their own request's logs.
 *
 * Also emits one request_completed event per request (method, path, status, duration).
 * Without it the JSON log has domain events but no way to tie a slow or failing call to
 * an HTTP status — and Spring's own access logging is neither structured nor on by
 * default. Ordered first so that everything downstream, including a 401 from
 * AuthenticationFilter, is logged with a correlation id already in the MDC.
 */
@Component
@Order(1)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /**
     * Paths that still get a correlation id but no request_completed line. The container
     * HEALTHCHECK polls /actuator/health every 10s and a metrics scrape is similar — left
     * in, they would be the overwhelming majority of a public log stream and bury the
     * domain events it exists to show.
     */
    private static final String[] UNLOGGED_PREFIXES = {"/actuator", "/metrics", "/dashboard"};

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        long startedAtNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (shouldLog(request.getRequestURI())) {
                log.info("request completed",
                        kv("event", "request_completed"),
                        kv("method", request.getMethod()),
                        kv("path", request.getRequestURI()),
                        kv("status", response.getStatus()),
                        kv("durationMs", (System.nanoTime() - startedAtNanos) / 1_000_000));
            }
            // Tomcat reuses threads across requests — leaving this in MDC would leak
            // this request's id into whatever the next request on this thread logs.
            MDC.remove(MDC_KEY);
        }
    }

    private static boolean shouldLog(String path) {
        for (String prefix : UNLOGGED_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return false;
            }
        }
        return true;
    }
}