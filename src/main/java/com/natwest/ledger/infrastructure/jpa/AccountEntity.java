package com.natwest.ledger.infrastructure.jpa;

import com.natwest.ledger.domain.Account;
import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * The database row behind an {@link Account}.
 *
 * <p><b>Separate from the domain model on purpose.</b> Annotating {@code Account} directly would be
 * fewer files, but it would force the aggregate to carry a no-arg constructor and mutable setters
 * purely to satisfy Hibernate - the exact things {@code Account} avoids in order to keep the
 * overdraft rule enforceable. It would also let a schema change silently alter the domain. The cost
 * is this mapping class; the benefit is that the model stays answerable only to the business.
 *
 * <p><b>{@link Version} is the point of this phase.</b> Without it, two concurrent withdrawals can
 * both read a balance of 100, both decide 60 is affordable, and both write their own result - the
 * second overwriting the first, leaving 40 where it should have been -20 and rejected. Hibernate
 * includes the version in the {@code UPDATE ... WHERE version = ?} predicate, so the second write
 * affects no rows and fails loudly instead of quietly destroying the first. This is what makes the
 * no-overdraft guarantee hold under concurrency rather than only in a single-threaded test.
 *
 * <p>Money is stored as an amount plus an ISO currency code, never as a floating-point column.
 * {@code DECIMAL(19,2)} preserves exactly what {@link Money} promises.
 */
@Entity
@Table(name = "account")
class AccountEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "holder_name", nullable = false, length = 140)
    private String holderName;

    @Column(name = "balance_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal balanceAmount;

    @Column(name = "balance_currency", nullable = false, length = 3)
    private String balanceCurrency;

    /**
     * Incremented by Hibernate on every update; a stale value makes the write fail.
     *
     * <p>Never set by application code, which is why there is no setter.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Required by JPA. Not for application use. */
    protected AccountEntity() {
    }

    private AccountEntity(String id, String holderName, BigDecimal balanceAmount, String balanceCurrency) {
        this.id = id;
        this.holderName = holderName;
        this.balanceAmount = balanceAmount;
        this.balanceCurrency = balanceCurrency;
    }

    static AccountEntity newFor(Account account) {
        return new AccountEntity(
                account.id().value(),
                account.holderName(),
                account.balance().amount(),
                account.balance().currency().getCurrencyCode());
    }

    /**
     * Copies the current state of an account onto this row.
     *
     * <p>Applied to the instance already loaded in this transaction, so the version read at load
     * time is the one checked at flush time. Constructing a detached copy instead would discard that
     * version and disable the optimistic check altogether.
     */
    void apply(Account account) {
        this.holderName = account.holderName();
        this.balanceAmount = account.balance().amount();
        this.balanceCurrency = account.balance().currency().getCurrencyCode();
    }

    /**
     * Rebuilds the domain object.
     *
     * <p>Uses {@link Account#reconstitute} rather than {@code open}, because a persisted balance is a
     * historical fact to be restored faithfully, not a new request to be validated.
     */
    Account toDomain() {
        return Account.reconstitute(
                AccountId.of(id),
                holderName,
                Money.of(balanceAmount, Currency.getInstance(balanceCurrency)));
    }
}
