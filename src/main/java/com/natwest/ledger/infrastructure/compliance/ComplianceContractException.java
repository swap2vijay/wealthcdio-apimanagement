package com.natwest.ledger.infrastructure.compliance;

/**
 * The compliance service rejected our request, or answered in a shape we do not understand.
 *
 * <p>Distinct from {@link ComplianceCallFailedException} for one important reason: this indicates a
 * defect on <em>our</em> side - a malformed request, or a contract that has drifted - not a service in
 * distress. It is therefore configured to be ignored by both the retry and the circuit breaker.
 *
 * <p>Retrying a 400 produces another 400, and counting it as a breaker failure would be actively
 * harmful: a bug in our request payload would trip the circuit and stop us calling a service that is
 * perfectly healthy, turning a small defect into an outage.
 */
public class ComplianceContractException extends RuntimeException {

    public ComplianceContractException(String message) {
        super(message);
    }
}
