package com.natwest.ledger.client;

/**
 * What compliance screening concluded about a transfer.
 *
 * <p><b>Three states, not two.</b> "We could not ask" is genuinely different from "we asked and the
 * answer was no", and collapsing them into a boolean is how a service ends up letting payments
 * through during an outage. Making {@link #UNAVAILABLE} a first-class outcome forces every caller to
 * decide what to do about it rather than defaulting to the happy path.
 */
public enum ComplianceOutcome {

    /** Screened and cleared to proceed. */
    APPROVED,

    /** Screened and refused. A definitive answer - retrying will produce the same result. */
    REJECTED,

    /**
     * Screening could not be performed: the service was unreachable, too slow, or the circuit was
     * open. Says nothing about whether the transfer is acceptable, only that nobody knows.
     */
    UNAVAILABLE
}

