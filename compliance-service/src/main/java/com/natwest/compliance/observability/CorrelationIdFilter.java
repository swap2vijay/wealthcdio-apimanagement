package com.natwest.compliance.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.ThreadContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Adopts the caller's correlation id so this service's logs join up with the ledger service's.
 *
 * <p>This is what makes the pair of services debuggable as one system: a screening decision logged here
 * carries the same identifier as the transfer that requested it, so "why was this payment stopped?" is a
 * single search rather than an exercise in matching timestamps across two log streams.
 *
 * <p><b>Duplicated from the ledger service rather than shared.</b> A common library would couple the two
 * services' release cycles, which is precisely what keeping them independent is meant to avoid - and a
 * shared jar for thirty lines of filter is a poor trade. The duplication is deliberate and noted.
 *
 * <p><b>The supplied id is sanitised, not trusted.</b> It goes straight into log lines, so a newline
 * would let a caller forge log entries. Anything that is not a short plain token is replaced.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String correlationId = sanitiseOrGenerate(request.getHeader(HEADER));

        ThreadContext.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Servlet threads are pooled: a value left behind would be stamped onto the next,
            // unrelated request.
            ThreadContext.remove(MDC_KEY);
        }
    }

    static String sanitiseOrGenerate(String supplied) {
        if (supplied == null) {
            return UUID.randomUUID().toString();
        }
        String trimmed = supplied.trim();
        return ACCEPTABLE.matcher(trimmed).matches() ? trimmed : UUID.randomUUID().toString();
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}

