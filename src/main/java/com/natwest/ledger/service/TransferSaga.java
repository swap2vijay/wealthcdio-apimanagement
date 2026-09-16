package com.natwest.ledger.service;

import com.natwest.ledger.client.ComplianceAssessment;
import com.natwest.ledger.client.ComplianceGateway;
import com.natwest.ledger.client.ComplianceOutcome;
import com.natwest.ledger.exception.AccountNotFoundException;
import com.natwest.ledger.exception.ComplianceRejectedException;
import com.natwest.ledger.exception.ComplianceUnavailableException;
import com.natwest.ledger.exception.InsufficientFundsException;
import com.natwest.ledger.exception.LedgerException;
import com.natwest.ledger.exception.SameAccountTransferException;
import com.natwest.ledger.exception.TransferCompensationFailedException;
import com.natwest.ledger.model.Account;
import com.natwest.ledger.model.AccountId;
import com.natwest.ledger.model.LedgerEntry;
import com.natwest.ledger.model.Money;
import com.natwest.ledger.model.TransactionReference;

import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Runs a transfer as an orchestrated saga: reserve, screen, complete - and unwind if any step refuses.
 *
 * <p><b>Why a saga is needed here, and only here.</b> Until Phase 5 a transfer was a single database
 * transaction, which is strictly simpler and strictly safer: both balance updates and both ledger
 * entries committed together or not at all. Nothing about two accounts requires a saga. What requires
 * one is the compliance check now sitting between the two halves of the transfer.
 *
 * <p>The alternative would be to hold the database transaction open across the HTTP call to compliance.
 * That is considerably worse: row locks would be held for the duration of a network round trip, a
 * connection would be pinned out of the pool for as long as the dependency was slow, and a timeout
 * would leave the whole thing at the mercy of a service this one does not control. And it would buy
 * nothing, because a database rollback cannot undo an external call anyway.
 *
 * <p>So the transaction is deliberately broken into pieces, and the price is paid honestly: there is
 * now a window in which money has left the source account and not yet arrived at the destination. The
 * saga's job is to guarantee that the window always closes - by completing, or by putting the money
 * back.
 *
 * <p><b>This class is not transactional, and must not become so.</b> A transaction spanning the whole
 * method would defeat the entire design: the debit would no longer be committed before the remote call,
 * so it would not be durable, the funds would not really be reserved, and compensation would be
 * pointless because a rollback would already have undone it. The absence of {@code @Transactional} here
 * is load-bearing.
 *
 * <p><b>Reserve before screening, not after.</b> Screening first would avoid ever writing a reversal,
 * and would give a tidier statement for refused payments. Debiting first is chosen because it reserves
 * the funds: once compliance approves, the money is certainly still there, whereas screening first
 * leaves a window in which a concurrent withdrawal empties the account and the approved transfer then
 * fails on funds. The cost is that a refused transfer leaves a debit and a matching reversal on the
 * statement - which is arguably the more complete audit trail anyway, since an attempt really was made.
 */
@Service
public class TransferSaga {

    private static final Logger log = LogManager.getLogger(TransferSaga.class);

    private static final int MAX_NARRATIVE_LENGTH = 140;

    /**
     * How many times to try putting the money back before declaring it stranded.
     *
     * <p>Bounded rather than unlimited: a saga step must not retry forever holding a request thread. If
     * a handful of attempts cannot succeed, the condition needs a human, and a real system would hand it
     * to a recovery process rather than keep spinning here.
     */
    private static final int MAX_COMPENSATION_ATTEMPTS = 5;

    private static final Duration COMPENSATION_BACKOFF = Duration.ofMillis(25);

    /** Logging context key, surfaced by the log pattern as {@code %X{transferReference}}. */
    private static final String TRANSFER_REFERENCE_KEY = "transferReference";

    private final TransferSagaSteps steps;
    private final ComplianceGateway compliance;
    private final Clock clock;

    public TransferSaga(TransferSagaSteps steps, ComplianceGateway compliance, Clock clock) {
        this.steps = steps;
        this.compliance = compliance;
        this.clock = clock;
    }

    /**
     * Moves money between two accounts, screening it on the way.
     *
     * @throws SameAccountTransferException          source and destination are the same account
     * @throws AccountNotFoundException              either account does not exist
     * @throws InsufficientFundsException            the source cannot cover the amount
     * @throws ComplianceRejectedException           compliance refused it; the debit has been reversed
     * @throws ComplianceUnavailableException        compliance could not be reached; the debit has been reversed
     * @throws TransferCompensationFailedException   the reversal itself failed; needs intervention
     */
    public TransferReceipt execute(AccountId sourceId,
                                   AccountId destinationId,
                                   Money amount,
                                   String narrative) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(destinationId, "destinationId");
        Objects.requireNonNull(amount, "amount");

        if (sourceId.equals(destinationId)) {
            throw new SameAccountTransferException(sourceId);
        }

        TransactionReference reference = TransactionReference.newReference();

        // Stamp the reference onto the logging context for the whole saga. A transfer produces log
        // lines from several steps and possibly a compensation, and interleaved with other requests
        // they are otherwise impossible to group. Scoped with try-with-resources so it is removed even
        // when a step throws - a pooled thread must not carry one transfer's reference into the next.
        try (CloseableThreadContext.Instance ignored =
                     CloseableThreadContext.put(TRANSFER_REFERENCE_KEY, reference.value())) {

            // One instant for the whole transfer, taken once, so both legs and any reversal agree on
            // when it happened rather than drifting apart across separately committed steps.
            Instant occurredAt = clock.instant();

            // Refuse an impossible transfer before any money moves, rather than debiting and unwinding.
            steps.requireBothAccountsExist(sourceId, destinationId);

            // --- Step 1: reserve the funds. Commits, so they are genuinely held. ---
            LedgerEntry debit = steps.debitSource(sourceId, amount, reference, occurredAt,
                    narrativeOr(narrative, "Transfer to " + destinationId));

            log.info("Transfer {} reserved {} from {}; screening", reference, amount, sourceId);

            // --- Step 2: screen it. Cannot throw for transport failure; reports UNAVAILABLE instead. ---
            ComplianceAssessment assessment = compliance.screen(reference, sourceId, destinationId, amount);

            if (!assessment.isApproved()) {
                // Fail closed. A refusal and an outage are both reasons not to proceed; they differ only
                // in what the caller is told, because only one of them is worth retrying.
                unwind(reference, sourceId, amount, occurredAt,
                        "compliance %s (%s)".formatted(assessment.outcome(), assessment.reason()));

                throw refusalFor(assessment);
            }

            // --- Step 3: complete the transfer. ---
            LedgerEntry credit;
            try {
                credit = steps.creditDestination(destinationId, amount, reference, occurredAt,
                        narrativeOr(narrative, "Transfer from " + sourceId));

            } catch (RuntimeException creditFailure) {
                // Screening approved it but the credit did not land - a lost optimistic lock race, or
                // the database becoming unavailable. The money is still out of the source account, so it
                // has to go back regardless of what went wrong.
                log.error("Transfer {} was approved but crediting {} failed; reversing",
                        reference, destinationId, creditFailure);

                unwind(reference, sourceId, amount, occurredAt,
                        "credit to " + destinationId + " failed");

                throw creditFailure;
            }

            log.info("Transfer {} completed: {} from {} to {}", reference, amount, sourceId, destinationId);
            return new TransferReceipt(reference, debit, credit);
        }
    }

    /**
     * Chooses which failure to report for a transfer compliance would not clear.
     *
     * <p>An exhaustive switch with no default, so a new {@link ComplianceOutcome} cannot be added
     * without deciding what a transfer should do about it. The {@code APPROVED} arm is unreachable
     * because the caller has already checked, but stating it keeps the switch total.
     */
    private static LedgerException refusalFor(ComplianceAssessment assessment) {
        return switch (assessment.outcome()) {
            case REJECTED -> new ComplianceRejectedException(assessment);
            case UNAVAILABLE -> new ComplianceUnavailableException(assessment);
            case APPROVED -> throw new IllegalStateException(
                    "an approved assessment must not reach the refusal path");
        };
    }

    /**
     * Puts the reserved money back, retrying if it loses a race.
     *
     * <p><b>Why compensation retries when the forward path does not.</b> This was discovered rather than
     * designed. Running transfers concurrently showed the credit step losing an optimistic-lock race,
     * the saga correctly starting to compensate - and the compensation then losing a race of its own,
     * against the very contention that caused the original failure. A single attempt therefore stranded
     * money regularly under load, not rarely.
     *
     * <p>The asymmetry is the point. When a forward step fails, there is a caller holding an error
     * response who can decide to try again. When compensation fails there is nobody: the request is
     * already over, and the money is already gone. Compensation is the one step that has to keep trying.
     *
     * <p>Retrying is safe because each step commits in its own transaction, so a failed attempt rolled
     * back completely and left nothing behind. Without that guarantee a retry could credit the account
     * twice, which would be worse than the problem it solves.
     *
     * <p>Only an optimistic-lock failure is retried. It is transient by definition - somebody else got
     * there first - so another attempt is likely to succeed. Any other failure is treated as permanent,
     * because repeating a call against a genuinely broken ledger just delays the alarm.
     *
     * <p>If every attempt fails, the situation is logged unmistakably and reported as its own error
     * rather than folded into the original failure. Reporting only "compliance refused" would be a lie:
     * the caller would reasonably conclude no money had moved.
     */
    private void unwind(TransactionReference reference,
                        AccountId sourceId,
                        Money amount,
                        Instant occurredAt,
                        String reason) {

        for (int attempt = 1; attempt <= MAX_COMPENSATION_ATTEMPTS; attempt++) {
            try {
                steps.reverseDebit(sourceId, amount, reference, occurredAt, reversalNarrative(reason));
                log.info("Transfer {} reversed {} back to {} because {}", reference, amount, sourceId, reason);
                return;

            } catch (OptimisticLockingFailureException lostRace) {
                log.warn("Transfer {} reversal attempt {}/{} lost a race on {}; retrying",
                        reference, attempt, MAX_COMPENSATION_ATTEMPTS, sourceId);

                if (!pauseBefore(attempt + 1)) {
                    break;
                }

            } catch (RuntimeException permanentFailure) {
                log.error("Transfer {} reversal failed permanently on {}", reference, sourceId, permanentFailure);
                break;
            }
        }

        log.fatal("Transfer {} debited {} from {} and the reversal FAILED after {} attempt(s). "
                        + "Funds have left the account and arrived nowhere. Manual reconciliation required.",
                reference, amount, sourceId, MAX_COMPENSATION_ATTEMPTS);

        throw new TransferCompensationFailedException(reference, sourceId, amount, reason);
    }

    /**
     * Backs off briefly before the next compensation attempt.
     *
     * <p>Blocking, which is acceptable only because the wait is short and bounded, and because the
     * alternative - giving up - strands a customer's money. Backing off at all matters: retrying
     * instantly against a contended row tends to lose the same race again.
     *
     * @return false if the thread was interrupted and retrying should stop
     */
    private static boolean pauseBefore(int attempt) {
        try {
            Thread.sleep(COMPENSATION_BACKOFF.multipliedBy(attempt).toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Keeps the reversal's explanation within the ledger's narrative column. */
    private static String reversalNarrative(String reason) {
        String narrative = "Reversed: " + reason;
        return narrative.length() <= MAX_NARRATIVE_LENGTH
                ? narrative
                : narrative.substring(0, MAX_NARRATIVE_LENGTH);
    }

    private static String narrativeOr(String supplied, String fallback) {
        return (supplied == null || supplied.isBlank()) ? fallback : supplied;
    }
}

