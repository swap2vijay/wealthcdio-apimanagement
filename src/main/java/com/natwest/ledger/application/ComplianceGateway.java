package com.natwest.ledger.application;

import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.Money;
import com.natwest.ledger.domain.TransactionReference;

/**
 * The application's view of compliance screening.
 *
 * <p>Another port, declared by the consumer, for the same reason as the repositories: the transfer
 * logic should be expressible and testable without a second service running. A test can supply a
 * gateway that approves, refuses, or is unavailable, and exercise all three paths in milliseconds.
 *
 * <p><b>It does not throw when the downstream service is unwell.</b> A failure to reach compliance is
 * returned as {@link ComplianceOutcome#UNAVAILABLE}, not raised as an exception. That is deliberate:
 * an unreachable dependency is an expected operating condition for a distributed system, not an
 * exceptional one, and returning it forces the caller to handle all three outcomes explicitly rather
 * than letting one slip past in a {@code catch} block. The orchestrator decides the policy; this port
 * only reports facts.
 */
public interface ComplianceGateway {

    /**
     * Asks whether a transfer may proceed.
     *
     * <p>Never throws for a transport problem - see the class note. Implementations are expected to
     * apply their own timeouts and failure handling, so a caller never blocks indefinitely.
     *
     * @param reference   correlates this screening with the transfer in both services' logs
     * @param source      the account being debited
     * @param destination the account being credited
     * @param amount      how much is moving
     */
    ComplianceAssessment screen(TransactionReference reference,
                               AccountId source,
                               AccountId destination,
                               Money amount);
}
