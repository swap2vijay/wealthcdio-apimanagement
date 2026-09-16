package com.natwest.ledger.observability;

import org.apache.logging.log4j.ThreadContext;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The identifier that ties every log line of one request together, across both services.
 *
 * <p>Without it, diagnosing a failed transfer means correlating two services' logs by timestamp and
 * amount - which stops working the moment two similar payments happen in the same second, exactly when
 * you most need it to work.
 *
 * <p><b>A supplied correlation id is sanitised, never trusted.</b> This value comes from a request
 * header and is written straight into log lines, which makes it a log-injection vector: a newline lets
 * a caller forge whole log entries, and control characters can corrupt a log shipper or a terminal
 * reading the output. Anything that is not a short, plain token is discarded and replaced with a fresh
 * one. Rejecting rather than escaping is the safer choice here - there is no legitimate reason for a
 * correlation id to contain anything unusual.
 */
public final class CorrelationId {

    /** The header clients may supply, and which this service echoes back. */
    public static final String HEADER = "X-Correlation-Id";

    /** The key the log pattern reads, via {@code %X{correlationId}}. */
    public static final String MDC_KEY = "correlationId";

    /**
     * Letters, digits, dot, dash and underscore only, up to 64 characters.
     *
     * <p>Deliberately narrow: it accommodates a UUID, a trace id or a request id from an upstream
     * gateway, and nothing that could break a log line.
     */
    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationId() {
    }

    /**
     * Returns the supplied id if it is safe to log, otherwise a fresh one.
     *
     * <p>Never returns null and never throws: a request must not fail because its correlation header
     * was malformed. Tracing is an aid, not a precondition.
     */
    public static String sanitiseOrGenerate(String supplied) {
        if (supplied == null) {
            return generate();
        }
        String trimmed = supplied.trim();
        return ACCEPTABLE.matcher(trimmed).matches() ? trimmed : generate();
    }

    /** True when the value is safe to put in a log line as-is. */
    public static boolean isAcceptable(String candidate) {
        return candidate != null && ACCEPTABLE.matcher(candidate.trim()).matches();
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /** The correlation id of the request being handled on this thread, if one is in scope. */
    public static Optional<String> current() {
        return Optional.ofNullable(ThreadContext.get(MDC_KEY));
    }
}

