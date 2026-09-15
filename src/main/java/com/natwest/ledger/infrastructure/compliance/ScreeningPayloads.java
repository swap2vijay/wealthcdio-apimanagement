package com.natwest.ledger.infrastructure.compliance;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The wire format of the compliance service's API.
 *
 * <p>Deliberately its own set of types rather than reusing the compliance service's classes. Sharing
 * a jar between the two services would couple their release cycles and make a change to one force a
 * redeployment of the other - at which point they are one service in two processes. The contract
 * between them is JSON, and these records are this side's understanding of it.
 *
 * <p>{@code decision} is a String, not an enum. If the other service adds a decision this version has
 * never heard of, deserialisation must not explode: the gateway inspects the value and treats anything
 * unrecognised as a contract error, which is a controlled failure rather than a stack trace.
 */
final class ScreeningPayloads {

    private ScreeningPayloads() {
    }

    record Request(String reference,
                   String sourceAccountId,
                   String destinationAccountId,
                   BigDecimal amount,
                   String currency) {
    }

    record Response(String reference, String decision, String reason, Instant screenedAt) {
    }
}
