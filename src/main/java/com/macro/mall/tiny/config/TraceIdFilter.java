package com.macro.mall.tiny.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Pattern TRACEPARENT = Pattern.compile(
            "^00-([0-9a-f]{32})-[0-9a-f]{16}-[0-9a-f]{2}$", Pattern.CASE_INSENSITIVE);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Tracer tracer;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Span span = tracer.currentSpan();
        String traceId = span == null ? traceId(request.getHeader("traceparent")) : span.context().traceId();
        String spanId = span == null ? randomHex(8) : span.context().spanId();
        String traceFlags = span != null && Boolean.TRUE.equals(span.context().sampled()) ? "01" : "00";
        if (traceId == null) {
            traceId = randomHex(16);
        }
        MDC.put("traceId", traceId);
        response.setHeader("traceparent", "00-" + traceId + "-" + spanId + "-" + traceFlags);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }

    private String traceId(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        Matcher matcher = TRACEPARENT.matcher(traceparent.trim());
        return matcher.matches() ? matcher.group(1).toLowerCase() : null;
    }

    private String randomHex(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return HexFormat.of().formatHex(value);
    }
}
