package com.natwest.ledger.observability;

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

/**
 * Puts a correlation id on the logging context for the duration of every request.
 *
 * <p>Ordered first, so that anything else which logs during the request - including the exception
 * advice reporting a failure - already has the id available. A filter that ran later would leave the
 * earliest and often most interesting log lines uncorrelated.
 *
 * <p><b>The id is echoed in the response.</b> That is what makes it usable: a caller seeing a 500 can
 * quote the header in a support request, and the whole conversation can be found in the logs
 * immediately. An id that only exists server-side helps nobody who is reporting a problem.
 *
 * <p><b>Cleared in a finally block, always.</b> Servlet containers pool threads, so a value left behind
 * would be inherited by the next, unrelated request - stamping one customer's identifier onto another's
 * log lines. That is both a debugging trap and a small privacy leak, and it is exactly the kind of bug
 * that only appears under load.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String correlationId = CorrelationId.sanitiseOrGenerate(request.getHeader(CorrelationId.HEADER));

        ThreadContext.put(CorrelationId.MDC_KEY, correlationId);

        // Set before the chain runs, so the header is present even on a response written by an error
        // handler that never returns to this method normally.
        response.setHeader(CorrelationId.HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            ThreadContext.remove(CorrelationId.MDC_KEY);
        }
    }

    /**
     * Also runs for actuator and error dispatches.
     *
     * <p>Health checks and error forwards are precisely the requests worth correlating, so the default
     * exclusion of non-request dispatches is overridden.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        return false;
    }
}
