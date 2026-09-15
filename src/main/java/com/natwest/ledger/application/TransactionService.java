package com.natwest.ledger.application;

import com.natwest.ledger.domain.Account;
import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.InsufficientFundsException;
import com.natwest.ledger.domain.LedgerEntry;
import com.natwest.ledger.domain.Money;
import com.natwest.ledger.domain.SameAccountTransferException;
import com.natwest.ledger.domain.TransactionReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Moving money: deposits, withdrawals and transfers.
 *
 * <p>Every operation follows the same shape - load the account, let the account decide, record what
 * it decided. The validation lives in {@link Account}, so this class contains no {@code if} guarding
 * a balance.
 *
 * <p><b>Transfers here are single-process and in-memory-atomic.</b> That is deliberate for this
 * phase: it is the simplest thing that satisfies the requirement, and it makes the sequencing
 * decisions (which leg first, which account to load first) explicit and testable before any
 * network hop exists. Phase 6 revisits this as an orchestrated saga once a second service is
 * genuinely involved, at which point "both or neither" can no longer be assumed.
 */
@Service
public class TransactionService {

    private static final Logger log = LogManager.getLogger(TransactionService.class);

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final Clock clock;

    public TransactionService(AccountRepository accounts, LedgerRepository ledger, Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * Pays money into an account.
     *
     * @throws AccountNotFoundException if no such account exists
     * @throws com.natwest.ledger.domain.InvalidAmountException     if the amount is not positive
     * @throws com.natwest.ledger.domain.CurrencyMismatchException  if the currency differs
     */
    public LedgerEntry deposit(AccountId accountId, Money amount, String narrative) {
        Account account = load(accountId);

        LedgerEntry entry = account.deposit(amount, TransactionReference.newReference(), clock.instant(), narrative);

        accounts.save(account);
        ledger.append(entry);
        log.info("Deposited {} into {} [ref={}] leaving {}",
                amount, accountId, entry.reference(), entry.balanceAfter());
        return entry;
    }

    /**
     * Takes money out of an account.
     *
     * @throws AccountNotFoundException    if no such account exists
     * @throws InsufficientFundsException  if the balance would go below zero
     */
    public LedgerEntry withdraw(AccountId accountId, Money amount, String narrative) {
        Account account = load(accountId);

        LedgerEntry entry = account.withdraw(amount, TransactionReference.newReference(), clock.instant(), narrative);

        accounts.save(account);
        ledger.append(entry);
        log.info("Withdrew {} from {} [ref={}] leaving {}",
                amount, accountId, entry.reference(), entry.balanceAfter());
        return entry;
    }

    /**
     * Moves money between two accounts.
     *
     * <p>Two ordering decisions matter here, and neither is accidental.
     *
     * <p><b>The accounts are loaded in a canonical order</b> (by identifier), not in the order the
     * caller named them. Two opposing simultaneous transfers - A to B and B to A - would otherwise
     * grab their rows in opposite sequences and deadlock. Sorting first means every transfer in the
     * system acquires locks in the same direction, so the cycle cannot form. Harmless with the
     * in-memory adapter; essential once Phase 4 puts real row locks behind this.
     *
     * <p><b>The source is debited before the destination is credited.</b> If funds are short, the
     * failure happens before anything has been credited, so there is no partial state to undo.
     *
     * @throws SameAccountTransferException if source and destination are the same account
     * @throws AccountNotFoundException     if either account does not exist
     * @throws InsufficientFundsException   if the source cannot cover the amount
     */
    public TransferReceipt transfer(AccountId sourceId, AccountId destinationId, Money amount, String narrative) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(destinationId, "destinationId");

        if (sourceId.equals(destinationId)) {
            throw new SameAccountTransferException(sourceId);
        }

        Account source;
        Account destination;
        if (sourceId.value().compareTo(destinationId.value()) <= 0) {
            source = load(sourceId);
            destination = load(destinationId);
        } else {
            destination = load(destinationId);
            source = load(sourceId);
        }

        TransactionReference reference = TransactionReference.newReference();
        Instant occurredAt = clock.instant();

        LedgerEntry debit = source.transferOut(amount, reference, occurredAt,
                narrativeOr(narrative, "Transfer to " + destinationId));
        LedgerEntry credit = destination.transferIn(amount, reference, occurredAt,
                narrativeOr(narrative, "Transfer from " + sourceId));

        accounts.save(source);
        accounts.save(destination);
        ledger.appendAll(List.of(debit, credit));

        log.info("Transferred {} from {} to {} [ref={}]", amount, sourceId, destinationId, reference);
        return new TransferReceipt(reference, debit, credit);
    }

    private Account load(AccountId accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    private static String narrativeOr(String supplied, String fallback) {
        return (supplied == null || supplied.isBlank()) ? fallback : supplied;
    }
}
