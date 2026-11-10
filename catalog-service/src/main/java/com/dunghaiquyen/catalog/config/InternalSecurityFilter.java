package com.dunghaiquyen.catalog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class InternalSecurityFilter extends OncePerRequestFilter {
    private final byte[] expected;
    private final ObjectMapper mapper;

    public InternalSecurityFilter(@Value("${app.internal-token}") String token, ObjectMapper mapper) {
        this.expected = token.getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader("X-Correlation-Id");
        String correlationId = incoming != null && incoming.matches("[A-Za-z0-9._:-]{1,100}") ? incoming : UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        response.setHeader("X-Correlation-Id", correlationId);
        try {
            if (request.getRequestURI().startsWith("/internal/") && !matches(request.getHeader("X-Internal-Service-Token"))) {
                response.setStatus(401); response.setContentType("application/json");
                mapper.writeValue(response.getOutputStream(), Map.of("status", 401, "message", "Invalid service token", "correlationId", correlationId));
                return;
            }
            chain.doFilter(request, response);
        } finally { MDC.remove("correlationId"); }
    }

    private boolean matches(String actual) {
        return actual != null && MessageDigest.isEqual(expected, actual.getBytes(StandardCharsets.UTF_8));
    }
}
