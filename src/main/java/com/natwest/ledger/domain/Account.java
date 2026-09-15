package com.natwest.ledger.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * An account: a unique identity, a holder, and a balance that only the account itself may change.
 *
 * <p><b>The balance is not settable.</b> There is no {@code setBalance}. Callers ask the account to
 * {@link #deposit} or {@link #withdraw}, and the account decides whether that is allowed. This is
 * the difference between a model that enforces its rules and a bag of data that hopes every caller
 * remembers to check first - and it is why the overdraft rule cannot be bypassed by forgetting a
 * validation.
 *
 * <p><b>Every debit funnels through one method.</b> Withdrawals, outbound transfers, and any future
 * debit all route through the private {@link #debit} method, which is the single place the
 * zero-floor guard is applied. Adding a new kind of debit therefore inherits the rule instead of
 * having to remember it.
 *
 * <p><b>Operations return the ledger entry they caused.</b> Rather than mutating the balance and
 * leaving the caller to write a matching audit record - two steps that can silently disagree - each
 * operation returns the {@link LedgerEntry} describing exactly what it did, including the resulting
 * balance. The balance change and its record are produced together, by the object that owns both.
 *
 * <p><b>Time is supplied, not read.</b> No call to {@code Instant.now()} appears here. The caller
 * passes the instant, which keeps the domain deterministic and its tests free of sleeps or clock
 * stubbing. The application layer owns a {@link java.time.Clock}.
 *
 * <p>Not thread-safe by design. Concurrency is handled at the transaction boundary with optimistic
 * locking (Phase 4) rather than by synchronising the aggregate, because the operations that must be
 * serialised span the database, not just this object.
 */
public class Account {

    private static final int MAX_HOLDER_NAME_LENGTH = 140;

    private final AccountId id;
    private final String holderName;
    private Money balance;

    private Account(AccountId id, String holderName, Money balance) {
        this.id = id;
        this.holderName = holderName;
        this.balance = balance;
    }

    /**
     * Opens a new account.
     *
     * <p>A zero opening balance is allowed; a negative one is not, since that would create an
     * account already in overdraft and contradict the rule the rest of this class enforces.
     */
    public static Account open(AccountId id, String holderName, Money openingBalance) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(openingBalance, "openingBalance");
        if (openingBalance.isNegative()) {
            throw new InvalidAmountException(
                    "Opening balance cannot be negative but was %s".formatted(openingBalance));
        }
        return new Account(id, validHolderName(holderName), openingBalance);
    }

    /**
     * Rebuilds an account from storage without re-running the opening rules.
     *
     * <p>Separate from {@link #open} because loading is not opening: a balance that is already
     * persisted is a historical fact and must be restored faithfully, even if the rules that
     * governed its creation have since changed.
     */
    public static Account reconstitute(AccountId id, String holderName, Money balance) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(balance, "balance");
        return new Account(id, holderName, balance);
    }

    private static String validHolderName(String holderName) {
        if (holderName == null || holderName.isBlank()) {
            throw new IllegalArgumentException("An account holder name is required");
        }
        String trimmed = holderName.trim();
        if (trimmed.length() > MAX_HOLDER_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Account holder name must not exceed %d characters".formatted(MAX_HOLDER_NAME_LENGTH));
        }
        return trimmed;
    }

    public AccountId id() {
        return id;
    }

    public String holderName() {
        return holderName;
    }

    public Money balance() {
        return balance;
    }

    /** Pays money in. */
    public LedgerEntry deposit(Money amount, TransactionReference reference, Instant occurredAt, String narrative) {
        return credit(TransactionType.DEPOSIT, amount, reference, occurredAt, narrative, "A deposit");
    }

    /**
     * Takes money out.
     *
     * @throws InsufficientFundsException if the balance would fall below zero
     */
    public LedgerEntry withdraw(Money amount, TransactionReference reference, Instant occurredAt, String narrative) {
        return debit(TransactionType.WITHDRAWAL, amount, reference, occurredAt, narrative, "A withdrawal");
    }

    /**
     * The debit leg of an outbound transfer.
     *
     * @throws InsufficientFundsException if the balance would fall below zero
     */
    public LedgerEntry transferOut(Money amount, TransactionReference reference, Instant occurredAt, String narrative) {
        return debit(TransactionType.TRANSFER_OUT, amount, reference, occurredAt, narrative, "A transfer");
    }

    /** The credit leg of an inbound transfer. */
    public LedgerEntry transferIn(Money amount, TransactionReference reference, Instant occurredAt, String narrative) {
        return credit(TransactionType.TRANSFER_IN, amount, reference, occurredAt, narrative, "A transfer");
    }

    /**
     * Puts back money debited by a transfer whose later steps failed.
     *
     * <p>A credit rather than an edit of the original debit, so the ledger stays append-only and the
     * statement tells the truth: the money left, then it came back.
     */
    public LedgerEntry reverseTransfer(Money amount, TransactionReference reference, Instant occurredAt, String narrative) {
        return credit(TransactionType.TRANSFER_REVERSAL, amount, reference, occurredAt, narrative, "A reversal");
    }

    private LedgerEntry credit(TransactionType type,
                               Money amount,
                               TransactionReference reference,
                               Instant occurredAt,
                               String narrative,
                               String operation) {
        requireMovable(amount, operation);
        // plus() rejects a currency that differs from the balance, so no FX can slip in.
        balance = balance.plus(amount);
        return LedgerEntry.of(reference, id, type, amount, balance, occurredAt, narrative);
    }

    /**
     * The single gate through which money leaves an account.
     *
     * <p>Order matters: validate the amount, then the currency, then affordability. Checking
     * affordability first would report "insufficient funds" for a request that was actually
     * malformed, sending the caller off to investigate the wrong problem.
     */
    private LedgerEntry debit(TransactionType type,
                              Money amount,
                              TransactionReference reference,
                              Instant occurredAt,
                              String narrative,
                              String operation) {
        requireMovable(amount, operation);
        if (!balance.isAtLeast(amount)) {
            throw new InsufficientFundsException(id, balance, amount);
        }
        balance = balance.minus(amount);
        return LedgerEntry.of(reference, id, type, amount, balance, occurredAt, narrative);
    }

    private void requireMovable(Money amount, String operation) {
        Objects.requireNonNull(amount, "amount");
        amount.requirePositiveFor(operation);
        if (!amount.currency().equals(balance.currency())) {
            throw new CurrencyMismatchException(balance.currency(), amount.currency());
        }
    }

    /**
     * Identity equality on {@link AccountId}.
     *
     * <p>An account is an entity, not a value: the same account with a different balance is still
     * the same account. Comparing balances here would break {@code Set} and {@code Map} membership
     * the moment money moved.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Account other)) {
            return false;
        }
        return id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Account[%s, holder=%s, balance=%s]".formatted(id, holderName, balance);
    }
}
