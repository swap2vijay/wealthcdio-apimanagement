package com.natwest.ledger.web;

import com.natwest.ledger.error.ErrorCode;
import com.natwest.ledger.error.LedgerException;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns every failure into one consistent response shape.
 *
 * <p><b>Why centralise it.</b> The alternative is a try/catch in every controller method. That
 * duplicates the mapping, and duplicated mappings drift: one endpoint starts returning 400 for a
 * failure another reports as 422, and clients end up special-casing endpoints. Handling it once means
 * the contract is defined in a single readable place.
 *
 * <p><b>RFC 7807.</b> Responses are {@code application/problem+json} using Spring's
 * {@link ProblemDetail}, rather than a bespoke error envelope. It costs nothing, and a standard shape
 * is one fewer thing for a client to learn. Two extensions are added:
 * <ul>
 *   <li>{@code code} - the stable {@link ErrorCode} clients should branch on. HTTP status alone is
 *       too coarse: several distinct business failures share 400, and a status cannot tell them
 *       apart.</li>
 *   <li>{@code details} - structured context, so an insufficient-funds failure reports the balance
 *       and shortfall as fields rather than as prose the client would have to parse.</li>
 * </ul>
 *
 * <p><b>Internal failures reveal nothing.</b> Anything unrecognised becomes a flat 500 carrying
 * {@code LDG-9001} and a fixed message. Stack traces, SQL and class names go to the log, never to the
 * caller - an error response is not a debugging channel, and leaking internals is how attackers map a
 * system.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so Spring's own handling of malformed requests,
 * unsupported methods and unreadable bodies is inherited rather than reimplemented; only the parts
 * that need this service's error codes are overridden.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LogManager.getLogger(GlobalExceptionHandler.class);

    /**
     * Namespace for problem types. A dereferenceable URI per error class is what RFC 7807 intends,
     * and it gives documentation somewhere to live.
     */
    private static final String PROBLEM_TYPE_BASE = "https://api.natwest.example/problems/";

    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /**
     * Every deliberate failure: business rule violations, missing accounts, duplicate ids.
     *
     * <p>The status comes from {@link ErrorCodeHttpStatus}, so the decision is made once for the
     * whole service rather than at each throw site.
     */
    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ProblemDetail> handleLedgerException(LedgerException exception,
                                                              HttpServletRequest request) {
        HttpStatus status = ErrorCodeHttpStatus.of(exception.errorCode());
        ProblemDetail problem = problemOf(status, exception.errorCode(), exception.getMessage(),
                request.getRequestURI());

        if (!exception.details().isEmpty()) {
            problem.setProperty("details", exception.details());
        }

        // A rejected withdrawal is normal business, not a system fault, so it is logged at WARN
        // without a stack trace. Filling the log with traces for expected outcomes trains people to
        // ignore it.
        log.warn("{} {} rejected: [{}] {}",
                request.getMethod(), request.getRequestURI(), exception.errorCode().code(), exception.getMessage());

        return ResponseEntity.status(status).body(problem);
    }

    /**
     * Arguments the domain considers structurally impossible - a blank holder name, an unrecognised
     * currency code.
     *
     * <p>These are genuine client mistakes rather than service faults, so they are reported as 400
     * instead of falling through to the catch-all 500.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException exception,
                                                              HttpServletRequest request) {
        ProblemDetail problem = problemOf(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                exception.getMessage(), request.getRequestURI());

        log.warn("{} {} rejected as malformed: {}",
                request.getMethod(), request.getRequestURI(), exception.getMessage());

        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * The last line of defence.
     *
     * <p>Deliberately says nothing specific. The full exception is logged for whoever is on call; the
     * caller gets a stable code and the reassurance that retrying may work.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        ProblemDetail problem = problemOf(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "The service was unable to process the request. Please retry, or contact support "
                        + "quoting the time of this response.",
                request.getRequestURI());

        log.error("Unhandled failure processing {} {}", request.getMethod(), request.getRequestURI(), exception);

        return ResponseEntity.internalServerError().body(problem);
    }

    /**
     * Bean validation failures, reported field by field.
     *
     * <p>Returns every violation rather than only the first. A client fixing a form should not have to
     * discover its mistakes one round trip at a time.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
                                                                 HttpHeaders headers,
                                                                 HttpStatusCode status,
                                                                 WebRequest request) {
        List<Map<String, Object>> fieldErrors = new ArrayList<>();

        exception.getBindingResult().getFieldErrors().forEach(error -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", error.getField());
            entry.put("message", error.getDefaultMessage());
            if (error.getRejectedValue() != null) {
                entry.put("rejectedValue", String.valueOf(error.getRejectedValue()));
            }
            fieldErrors.add(entry);
        });

        exception.getBindingResult().getGlobalErrors().forEach(error -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("field", error.getObjectName());
            entry.put("message", error.getDefaultMessage());
            fieldErrors.add(entry);
        });

        ProblemDetail problem = problemOf(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                "The request failed validation. See fieldErrors for what to correct.",
                pathOf(request));
        problem.setProperty("fieldErrors", fieldErrors);

        log.warn("{} rejected: {} validation error(s)", pathOf(request), fieldErrors.size());

        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * A body that could not be parsed at all - broken JSON, or text where a number was expected.
     *
     * <p>The underlying Jackson message is not echoed back: it exposes internal field and class names
     * and is unhelpful to a caller.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = problemOf(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                "The request body could not be read. It must be valid JSON matching the documented schema.",
                pathOf(request));

        log.warn("{} sent an unreadable body: {}", pathOf(request), exception.getMostSpecificCause().getMessage());

        return ResponseEntity.badRequest().body(problem);
    }

    private ProblemDetail problemOf(HttpStatus status, ErrorCode errorCode, String detail, String path) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(errorCode.title());
        problem.setType(URI.create(PROBLEM_TYPE_BASE + slugOf(errorCode)));
        problem.setProperty("code", errorCode.code());
        problem.setProperty("timestamp", clock.instant());
        if (path != null) {
            problem.setInstance(URI.create(path));
        }
        return problem;
    }

    /** {@code INSUFFICIENT_FUNDS} becomes {@code insufficient-funds}. */
    private static String slugOf(ErrorCode errorCode) {
        return errorCode.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String pathOf(WebRequest request) {
        return (request instanceof ServletWebRequest servletRequest)
                ? servletRequest.getRequest().getRequestURI()
                : null;
    }
}
