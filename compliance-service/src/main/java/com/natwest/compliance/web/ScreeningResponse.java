package com.natwest.compliance.web;

import com.natwest.compliance.domain.Decision;
import com.natwest.compliance.domain.ScreeningOutcome;

import java.time.Instant;

/**
 * The screening decision.
 *
 * <p>A rejection is returned as {@code 200 OK} with {@code decision: REJECTED}, not as a 4xx. That is
 * a deliberate choice: this service was asked a question and answered it successfully, so the call
 * did not fail. Encoding the answer in the status would make "we screened it and said no"
 * indistinguishable from "the screening request was malformed" - and, worse, indistinguishable from a
 * transport failure, which a circuit breaker must treat completely differently. A firm "no" should
 * never open a circuit.
 *
 * @param screenedAt when the decision was taken, for the audit trail
 */
public record ScreeningResponse(String reference, Decision decision, String reason, Instant screenedAt) {

    public static ScreeningResponse of(String reference, ScreeningOutcome outcome, Instant screenedAt) {
        return new ScreeningResponse(reference, outcome.decision(), outcome.reason(), screenedAt);
    }
}
