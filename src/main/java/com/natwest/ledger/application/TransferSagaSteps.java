package com.natwest.ledger.application;

import com.natwest.ledger.domain.Account;
import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.LedgerEntry;
import com.natwest.ledger.domain.Money;
import com.natwest.ledger.domain.TransactionReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * The individually committed steps of a transfer saga.
 *
 * <p><b>A separate bean from {@link TransferSaga}, and that is not an accident.</b> Spring applies
 * {@code @Transactional} with a proxy, so a method calling another method on {@code this} bypasses it
 * entirely. Had these steps been private methods of the orchestrator, every annotation here would have
 * been silently inert - the whole saga would have run in no transaction at all, and the tests would
 * still have passed against an in-memory store. Splitting the classes is what makes the transaction
 * boundaries real.
 *
 * <p><b>Each step commits independently.</b> {@link Propagation#REQUIRES_NEW} rather than the default,
 * because a saga step that joins a surrounding transaction is not a saga step: if an outer rollback can
 * undo it, then compensation is unnecessary, and if compensation runs anyway the account is credited
 * twice. Forcing a new transaction means the annotation states the requirement rather than depending on
 * every future caller remembering not to wrap it.
 *
 * <p><b>Each step touches exactly one account.</b> A pleasant consequence of splitting the transfer up:
 * no transaction here locks two account rows, so the deadlock that opposing transfers could previously
 * cause is now structurally impossible rather than merely avoided by careful ordering.
 */
@Service
public class TransferSagaSteps {

    private final AccountRepository accounts;
    private final LedgerRepository ledger;

    public TransferSagaSteps(AccountRepository accounts, LedgerRepository ledger) {
        this.accounts = accounts;
        this.ledger = ledger;
    }

    /**
     * Confirms both accounts exist before any money moves.
     *
     * <p>Checked up front so that a transfer naming a non-existent destination is refused outright
     * rather than debited, discovered, and reversed. The customer's statement should not carry a debit
     * and a reversal for a payment that was never possible.
     *
     * <p>Strictly this is check-then-act, and an account could in principle disappear between the two.
     * It cannot here, because this service has no way to close an account - a limitation that happens
     * to make the check sound. If closure were added, the destination credit would need to handle a
     * missing account and compensate.
     *
     * @throws AccountNotFoundException if either account is unknown
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void requireBothAccountsExist(AccountId sourceId, AccountId destinationId) {
        requireExists(sourceId);
        requireExists(destinationId);
    }

    /**
     * Step one: take the money out of the source account and commit.
     *
     * <p>Committing here is the point of the whole design. The funds become genuinely reserved, so a
     * concurrent withdrawal cannot spend them while screening is in progress, and the debit survives
     * this process being restarted mid-transfer.
     *
     * @throws com.natwest.ledger.domain.InsufficientFundsException if the source cannot cover it
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LedgerEntry debitSource(AccountId sourceId,
                                  Money amount,
                                  TransactionReference reference,
                                  Instant occurredAt,
                                  String narrative) {
        Account source = load(sourceId);
        LedgerEntry debit = source.transferOut(amount, reference, occurredAt, narrative);

        accounts.save(source);
        ledger.append(debit);
        return debit;
    }

    /** Step two: put the money into the destination account and commit. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LedgerEntry creditDestination(AccountId destinationId,
                                        Money amount,
                                        TransactionReference reference,
                                        Instant occurredAt,
                                        String narrative) {
        Account destination = load(destinationId);
        LedgerEntry credit = destination.transferIn(amount, reference, occurredAt, narrative);

        accounts.save(destination);
        ledger.append(credit);
        return credit;
    }

    /**
     * The compensating action: give the money back.
     *
     * <p>An offsetting credit, not a deletion of the debit. The ledger stays append-only, so the
     * statement tells the truth - the money left and came back - and an auditor can see that a transfer
     * was attempted and unwound rather than finding a gap where a row used to be.
     *
     * <p>Carries the same reference as the debit it undoes, so all three entries of a failed transfer
     * can be retrieved together.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LedgerEntry reverseDebit(AccountId sourceId,
                                    Money amount,
                                    TransactionReference reference,
                                    Instant occurredAt,
                                    String narrative) {
        Account source = load(sourceId);
        LedgerEntry reversal = source.reverseTransfer(amount, reference, occurredAt, narrative);

        accounts.save(source);
        ledger.append(reversal);
        return reversal;
    }

    private Account load(AccountId accountId) {
        return accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private void requireExists(AccountId accountId) {
        if (!accounts.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
    }
}
