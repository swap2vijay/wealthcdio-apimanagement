package com.natwest.ledger.infrastructure.jpa;

import com.natwest.ledger.domain.Account;
import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.LedgerEntry;
import com.natwest.ledger.domain.Money;
import com.natwest.ledger.domain.TransactionReference;
import com.natwest.ledger.domain.TransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Specifies that storing and reloading loses nothing.
 *
 * <p>A persistence layer that quietly rounds an amount, reorders a statement or drops a currency is
 * worse than one that fails outright, because the loss is only discovered during a reconciliation
 * weeks later. These tests pin down the properties that must survive a round trip.
 *
 * <p>Repository calls are wrapped in an explicit transaction, matching how the application layer
 * invokes them. That matters for {@code save}, which loads before it writes so the version read at
 * load time is the one checked at flush time.
 */
@SpringBootTest
@DisplayName("JPA persistence")
class JpaPersistenceTest {

    private static final AccountId ALICE = AccountId.of("ACC-1001");
    private static final AccountId BOB = AccountId.of("ACC-2002");
    private static final AccountId UNKNOWN = AccountId.of("ACC-9999");
    private static final Instant AT = Instant.parse("2026-04-20T10:15:30Z");

    @Autowired
    private JpaAccountRepository accounts;

    @Autowired
    private JpaLedgerRepository ledger;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void clearTables() {
        jdbcTemplate.execute("DELETE FROM ledger_entry");
        jdbcTemplate.execute("DELETE FROM account");
    }

    // ---------------------------------------------------------------- helpers

    private void inTransaction(Runnable work) {
        transactionTemplate.executeWithoutResult(status -> work.run());
    }

    private Account reload(AccountId accountId) {
        Account loaded = transactionTemplate.execute(status -> accounts.findById(accountId).orElse(null));
        assertThat(loaded).as("expected account %s to be stored", accountId).isNotNull();
        return loaded;
    }

    private boolean isStored(AccountId accountId) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> accounts.existsById(accountId)));
    }

    private List<LedgerEntry> entriesOf(AccountId accountId) {
        List<LedgerEntry> entries = transactionTemplate.execute(status -> ledger.findByAccountId(accountId));
        return entries == null ? List.of() : entries;
    }

    private List<LedgerEntry> entriesOf(AccountId accountId, int offset, int limit) {
        List<LedgerEntry> entries =
                transactionTemplate.execute(status -> ledger.findByAccountId(accountId, offset, limit));
        return entries == null ? List.of() : entries;
    }

    private long countOf(AccountId accountId) {
        Long count = transactionTemplate.execute(status -> ledger.countByAccountId(accountId));
        return count == null ? 0L : count;
    }

    private long versionOf(String accountId) {
        Long version = jdbcTemplate.queryForObject(
                "SELECT version FROM account WHERE id = ?", Long.class, accountId);
        return version == null ? -1L : version;
    }

    // --------------------------------------------------------------- accounts

    @Nested
    @DisplayName("for an account")
    class Accounts {

        @Test
        @DisplayName("restores every field exactly as it was stored")
        void roundTripsAnAccount() {
            inTransaction(() -> accounts.save(Account.open(ALICE, "Ada Lovelace", Money.gbp("1234.56"))));

            Account reloaded = reload(ALICE);

            assertThat(reloaded.id()).isEqualTo(ALICE);
            assertThat(reloaded.holderName()).isEqualTo("Ada Lovelace");
            assertThat(reloaded.balance()).isEqualTo(Money.gbp("1234.56"));
            assertThat(reloaded.balance().currency().getCurrencyCode()).isEqualTo("GBP");
        }

        @Test
        @DisplayName("keeps large amounts exact, with no floating-point drift")
        void keepsLargeAmountsExact() {
            inTransaction(() -> accounts.save(Account.open(ALICE, "Ada Lovelace", Money.gbp("99999999999.99"))));

            assertThat(reload(ALICE).balance().toPlainString()).isEqualTo("99999999999.99");
        }

        @Test
        @DisplayName("updates the existing row rather than inserting a second one")
        void updatesRatherThanDuplicating() {
            inTransaction(() -> accounts.save(Account.open(ALICE, "Ada Lovelace", Money.gbp("100.00"))));

            inTransaction(() -> {
                Account loaded = accounts.findById(ALICE).orElseThrow();
                loaded.deposit(Money.gbp("50.00"), TransactionReference.newReference(), AT, null);
                accounts.save(loaded);
            });

            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account WHERE id = ?", Integer.class, "ACC-1001");

            assertThat(rows).isEqualTo(1);
            assertThat(reload(ALICE).balance()).isEqualTo(Money.gbp("150.00"));
        }

        @Test
        @DisplayName("advances the version on every update, which is what makes lost updates detectable")
        void advancesVersionOnUpdate() {
            inTransaction(() -> accounts.save(Account.open(ALICE, "Ada Lovelace", Money.gbp("100.00"))));

            long initialVersion = versionOf("ACC-1001");

            inTransaction(() -> {
                Account loaded = accounts.findById(ALICE).orElseThrow();
                loaded.deposit(Money.gbp("1.00"), TransactionReference.newReference(), AT, null);
                accounts.save(loaded);
            });

            assertThat(versionOf("ACC-1001"))
                    .as("a stale version is the only thing that can reject a concurrent overwrite")
                    .isGreaterThan(initialVersion);
        }

        @Test
        @DisplayName("reports absence rather than inventing an empty account")
        void reportsAbsence() {
            assertThat(isStored(UNKNOWN)).isFalse();
        }
    }

    // ----------------------------------------------------------------- ledger

    @Nested
    @DisplayName("for the ledger")
    class Ledger {

        @Test
        @DisplayName("restores every field of an entry, including an absent narrative")
        void roundTripsAnEntry() {
            Account account = Account.open(ALICE, "Ada Lovelace", Money.gbp("0"));
            LedgerEntry written = account.deposit(Money.gbp("25.50"), TransactionReference.of("REF-1"), AT, null);

            inTransaction(() -> ledger.append(written));

            LedgerEntry reloaded = entriesOf(ALICE).get(0);

            assertThat(reloaded.entryId()).isEqualTo(written.entryId());
            assertThat(reloaded.reference()).isEqualTo(TransactionReference.of("REF-1"));
            assertThat(reloaded.accountId()).isEqualTo(ALICE);
            assertThat(reloaded.type()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(reloaded.amount()).isEqualTo(Money.gbp("25.50"));
            assertThat(reloaded.balanceAfter()).isEqualTo(Money.gbp("25.50"));
            assertThat(reloaded.occurredAt()).isEqualTo(AT);
            assertThat(reloaded.narrative()).isNull();
        }

        @Test
        @DisplayName("stores the transaction type by name, so inserting a new type cannot rewrite history")
        void storesTypeByName() {
            Account account = Account.open(ALICE, "Ada Lovelace", Money.gbp("100.00"));
            LedgerEntry entry = account.withdraw(Money.gbp("10.00"), TransactionReference.of("REF-1"), AT, null);

            inTransaction(() -> ledger.append(entry));

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT type FROM ledger_entry WHERE account_id = ?", String.class, "ACC-1001"))
                    .isEqualTo("WITHDRAWAL");
        }

        @Test
        @DisplayName("keeps a stable order when every entry shares the same timestamp")
        void keepsStableOrderWhenTimestampsTie() {
            // Every entry is stamped with the identical instant, which is exactly what happens to the
            // two legs of a transfer. Ordering by occurredAt alone would leave the result up to the
            // database; the insertion sequence gives a total order.
            Account account = Account.open(ALICE, "Ada Lovelace", Money.gbp("0"));
            List<LedgerEntry> written = new ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                written.add(account.deposit(
                        Money.gbp(i + ".00"), TransactionReference.of("REF-" + i), AT, "entry " + i));
            }
            inTransaction(() -> written.forEach(ledger::append));

            List<LedgerEntry> reloaded = entriesOf(ALICE);

            assertThat(reloaded).extracting(LedgerEntry::narrative)
                    .containsExactly("entry 1", "entry 2", "entry 3", "entry 4",
                            "entry 5", "entry 6", "entry 7", "entry 8");
            assertThat(reloaded).extracting(LedgerEntry::entryId)
                    .containsExactlyElementsOf(written.stream().map(LedgerEntry::entryId).toList());
        }

        @Test
        @DisplayName("returns only the requested window, in the same order")
        void pagesInOrder() {
            Account account = Account.open(ALICE, "Ada Lovelace", Money.gbp("0"));
            List<LedgerEntry> written = new ArrayList<>();
            for (int i = 1; i <= 7; i++) {
                written.add(account.deposit(
                        Money.gbp("1.00"), TransactionReference.of("REF-" + i), AT, "entry " + i));
            }
            inTransaction(() -> written.forEach(ledger::append));

            assertThat(entriesOf(ALICE, 0, 3)).extracting(LedgerEntry::narrative)
                    .containsExactly("entry 1", "entry 2", "entry 3");

            assertThat(entriesOf(ALICE, 3, 3)).extracting(LedgerEntry::narrative)
                    .containsExactly("entry 4", "entry 5", "entry 6");

            assertThat(entriesOf(ALICE, 6, 3))
                    .as("the final page may be partial")
                    .extracting(LedgerEntry::narrative)
                    .containsExactly("entry 7");

            assertThat(entriesOf(ALICE, 9, 3))
                    .as("past the end is empty, not an error")
                    .isEmpty();

            assertThat(countOf(ALICE)).isEqualTo(7L);
        }

        @Test
        @DisplayName("writes an entire batch, which is how both legs of a transfer are recorded")
        void appendsABatch() {
            Account alice = Account.open(ALICE, "Ada Lovelace", Money.gbp("100.00"));
            Account bob = Account.open(BOB, "Grace Hopper", Money.gbp("0"));
            TransactionReference reference = TransactionReference.of("REF-TRANSFER");

            LedgerEntry debit = alice.transferOut(Money.gbp("30.00"), reference, AT, "out");
            LedgerEntry credit = bob.transferIn(Money.gbp("30.00"), reference, AT, "in");

            inTransaction(() -> ledger.appendAll(List.of(debit, credit)));

            assertThat(entriesOf(ALICE)).singleElement()
                    .satisfies(e -> assertThat(e.type()).isEqualTo(TransactionType.TRANSFER_OUT));
            assertThat(entriesOf(BOB)).singleElement()
                    .satisfies(e -> assertThat(e.type()).isEqualTo(TransactionType.TRANSFER_IN));
            assertThat(entriesOf(ALICE).get(0).reference()).isEqualTo(entriesOf(BOB).get(0).reference());
        }

        @Test
        @DisplayName("returns an empty statement for an account with no entries")
        void returnsEmptyForUnknownAccount() {
            assertThat(entriesOf(UNKNOWN)).isEmpty();
            assertThat(countOf(UNKNOWN)).isZero();
        }
    }
}
