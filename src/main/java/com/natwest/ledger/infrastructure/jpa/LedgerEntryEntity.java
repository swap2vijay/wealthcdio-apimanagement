package com.natwest.ledger.infrastructure.jpa;

import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.LedgerEntry;
import com.natwest.ledger.domain.Money;
import com.natwest.ledger.domain.TransactionReference;
import com.natwest.ledger.domain.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

/**
 * The database row behind a {@link LedgerEntry}.
 *
 * <p><b>Why there is a {@code sequence} column.</b> Ordering a statement by {@code occurredAt} alone
 * is not deterministic: both legs of a transfer are stamped with the same instant by design, and any
 * two operations within the same clock tick tie. A tie means the database may return them in either
 * order, so a customer could refresh their statement and see two lines swap - and a running balance
 * that appears to go backwards. An auto-incrementing sequence gives a total order that matches the
 * order things actually happened, so the statement is stable across reads.
 *
 * <p>That is also why {@code sequence} is the primary key while {@code entryId} is a unique business
 * key: the natural ordering of the table is then the order of insertion, and paging through it is
 * both cheap and consistent.
 *
 * <p>No {@code @Version} here, and no setters. Ledger entries are immutable once written - the only
 * legitimate correction is another entry - so there is nothing to update and nothing to conflict over.
 */
@Entity
@Table(name = "ledger_entry",
        indexes = {
                // The one query the requirements ask for: a statement per account, in order.
                @Index(name = "idx_ledger_account_sequence", columnList = "account_id, entry_sequence"),
                // Correlating the legs of one transfer across two accounts.
                @Index(name = "idx_ledger_reference", columnList = "reference")
        })
class LedgerEntryEntity {

    /** Named {@code entry_sequence} rather than {@code sequence}, which is close to reserved in SQL. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "entry_sequence", nullable = false, updatable = false)
    private Long sequence;

    @Column(name = "entry_id", nullable = false, unique = true, updatable = false, length = 36)
    private UUID entryId;

    @Column(name = "reference", nullable = false, updatable = false, length = 64)
    private String reference;

    @Column(name = "account_id", nullable = false, updatable = false, length = 36)
    private String accountId;

    /**
     * Stored as the enum name, never its ordinal.
     *
     * <p>Ordinals are positional: inserting a new transaction type in the middle of the enum would
     * silently reinterpret every historical row. A ledger cannot afford that.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, updatable = false, length = 32)
    private TransactionType type;

    @Column(name = "amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, updatable = false, precision = 19, scale = 2)
    private BigDecimal balanceAfter;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "narrative", updatable = false, length = 140)
    private String narrative;

    /** Required by JPA. Not for application use. */
    protected LedgerEntryEntity() {
    }

    static LedgerEntryEntity from(LedgerEntry entry) {
        LedgerEntryEntity entity = new LedgerEntryEntity();
        entity.entryId = entry.entryId();
        entity.reference = entry.reference().value();
        entity.accountId = entry.accountId().value();
        entity.type = entry.type();
        entity.amount = entry.amount().amount();
        entity.balanceAfter = entry.balanceAfter().amount();
        entity.currency = entry.amount().currency().getCurrencyCode();
        entity.occurredAt = entry.occurredAt();
        entity.narrative = entry.narrative();
        return entity;
    }

    LedgerEntry toDomain() {
        Currency entryCurrency = Currency.getInstance(currency);
        return new LedgerEntry(
                entryId,
                TransactionReference.of(reference),
                AccountId.of(accountId),
                type,
                Money.of(amount, entryCurrency),
                Money.of(balanceAfter, entryCurrency),
                occurredAt,
                narrative);
    }
}
