package com.natwest.ledger.application;

import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.Money;
import com.natwest.ledger.domain.TransactionReference;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

/**
 * A compliance gateway whose answer the test chooses.
 *
 * <p>Exists because the saga's interesting behaviour is what it does with each of the three possible
 * answers, and driving those from a real HTTP stub would make every saga test slow and indirect. The
 * gateway's own HTTP behaviour is covered separately by {@code HttpComplianceGatewayTest}; here the port
 * is simply a switch.
 *
 * <p>Also records what it was asked, so a test can assert that screening happened with the right
 * reference and amount - and, just as importantly, that it did <em>not</em> happen when the transfer was
 * never viable.
 */
public class ProgrammableComplianceGateway implements ComplianceGateway {

    /** What the gateway was asked to screen. */
    public record ScreeningCall(TransactionReference reference,
                                AccountId source,
                                AccountId destination,
                                Money amount) {
    }

    private final List<ScreeningCall> calls = Collections.synchronizedList(new ArrayList<>());

    private volatile ComplianceOutcome outcome = ComplianceOutcome.APPROVED;
    private volatile String reason;

    public void approve() {
        this.outcome = ComplianceOutcome.APPROVED;
        this.reason = null;
    }

    public void reject(String reason) {
        this.outcome = ComplianceOutcome.REJECTED;
        this.reason = reason;
    }

    public void beUnavailable(String reason) {
        this.outcome = ComplianceOutcome.UNAVAILABLE;
        this.reason = reason;
    }

    @Override
    public ComplianceAssessment screen(TransactionReference reference,
                                       AccountId source,
                                       AccountId destination,
                                       Money amount) {
        calls.add(new ScreeningCall(reference, source, destination, amount));

        return switch (outcome) {
            case APPROVED -> ComplianceAssessment.approved(reference);
            case REJECTED -> ComplianceAssessment.rejected(reference, reason);
            case UNAVAILABLE -> ComplianceAssessment.unavailable(reference, reason);
        };
    }

    public int callCount() {
        return calls.size();
    }

    public List<ScreeningCall> calls() {
        return List.copyOf(calls);
    }

    public ScreeningCall lastCall() {
        if (calls.isEmpty()) {
            throw new IllegalStateException("compliance was never asked to screen anything");
        }
        return calls.get(calls.size() - 1);
    }

    public void reset() {
        calls.clear();
        approve();
    }
}
