package com.paytm.wallet.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.wallet.dto.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Resolves {@code Authorization: Bearer <token>} into the calling userId and stores it on
 * the request (see {@link CallerContext}). Requests without a valid token are rejected
 * with 401 before they reach a controller.
 *
 * Two paths are deliberately open:
 *  - POST /wallets, because that is where a caller obtains their token in the first place
 *    (chicken-and-egg: requiring a token to get a token would make the API unusable);
 *  - the observability endpoints (/actuator, /metrics, /dashboard), because the
 *    assignment asks for publicly viewable health and metrics. The dashboard is a read-only
 *    view of those same counters and exposes no wallet, balance or token data.
 *
 * Runs after CorrelationIdFilter (order 2 vs 1) so that a 401 is still logged with the
 * request's correlation id.
 */
@Component
@Order(2)
public class AuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Set<String> OPEN_PREFIXES = Set.of("/actuator", "/metrics", "/dashboard");

    private final ApiToken apiToken;
    private final ObjectMapper objectMapper;

    public AuthenticationFilter(ApiToken apiToken, ObjectMapper objectMapper) {
        this.apiToken = apiToken;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("POST".equalsIgnoreCase(request.getMethod()) && "/wallets".equals(path)) {
            return true;
        }
        return OPEN_PREFIXES.stream().anyMatch(prefix -> path.equals(prefix) || path.startsWith(prefix + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            reject(response, "Missing 'Authorization: Bearer <token>' header");
            return;
        }

        Optional<String> userId = apiToken.resolve(header.substring(BEARER_PREFIX.length()).trim());
        if (userId.isEmpty()) {
            reject(response, "Bearer token is invalid");
            return;
        }

        request.setAttribute(CallerContext.USER_ID_ATTRIBUTE, userId.get());
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        log.warn("request rejected: unauthenticated", kv("event", "unauthenticated"), kv("reason", message));
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.of("UNAUTHORIZED", message));
    }
}