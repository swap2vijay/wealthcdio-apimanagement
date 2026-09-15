package com.natwest.ledger.application;

import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.InsufficientFundsException;
import com.natwest.ledger.domain.InvalidAmountException;
import com.natwest.ledger.domain.LedgerEntry;
import com.natwest.ledger.domain.Money;
import com.natwest.ledger.domain.SameAccountTransferException;
import com.natwest.ledger.domain.TransactionType;
import com.natwest.ledger.infrastructure.memory.InMemoryAccountRepository;
import com.natwest.ledger.infrastructure.memory.InMemoryLedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Specifies the three money-movement use cases: deposit, withdrawal and transfer.
 *
 * <p>Every failure case asserts on the <em>stored</em> state afterwards, not just on the exception.
 * An operation that throws but has already mutated an account is far more dangerous than one that
 * simply fails, and only reloading from the repository can tell the two apart.
 */
@DisplayName("The transaction service")
class TransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-20T10:15:30Z");
    private static final Currency USD = Currency.getInstance("USD");

    private static final AccountId ALICE = AccountId.of("ACC-1001");
    private static final AccountId BOB = AccountId.of("ACC-2002");

    private InMemoryAccountRepository accounts;
    private InMemoryLedgerRepository ledger;
    private AccountService accountService;
    private TransactionService transactionService;

    private ProgrammableComplianceGateway compliance;

    @BeforeEach
    void setUp() {
        accounts = new InMemoryAccountRepository();
        ledger = new InMemoryLedgerRepository();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        // Transfers now go through the saga, which screens them. These tests are about the money
        // movement rather than the screening, so compliance approves by default; the saga's own tests
        // cover what happens when it does not.
        compliance = new ProgrammableComplianceGateway();
        TransferSaga transferSaga = new TransferSaga(
                new TransferSagaSteps(accounts, ledger), compliance, clock);

        accountService = new AccountService(accounts, ledger, clock);
        transactionService = new TransactionService(accounts, ledger, transferSaga, clock);
    }

    /**
     * Opens a funded account.
     *
     * <p>Note that a positive opening balance appends an "Opening balance" entry, so a freshly
     * seeded account already has history. Tests below therefore assert on the <em>last</em> entry
     * and on changes in ledger size, never on absolute positions.
     */
    private void givenAccount(AccountId id, String holder, String balance) {
        accountService.openAccount(id, holder, Money.gbp(balance));
    }

    /** Reads the balance back out of the store, so a forgotten save cannot pass unnoticed. */
    private Money storedBalanceOf(AccountId id) {
        return accounts.findById(id).orElseThrow().balance();
    }

    private int ledgerSizeOf(AccountId id) {
        return ledger.findByAccountId(id).size();
    }

    /** The most recent entry, which is the one the operation under test just wrote. */
    private LedgerEntry lastEntryOf(AccountId id) {
        List<LedgerEntry> entries = ledger.findByAccountId(id);
        assertThat(entries).as("expected at least one ledger entry for %s", id).isNotEmpty();
        return entries.get(entries.size() - 1);
    }

    @Nested
    @DisplayName("when depositing")
    class Depositing {

        @Test
        @DisplayName("increases the balance and persists it")
        void depositsAndPersists() {
            givenAccount(ALICE, "Ada Lovelace", "100.00");

            LedgerEntry entry = transactionService.deposit(ALICE, Money.gbp("50.00"), "Salary");

            assertThat(entry.balanceAfter()).isEqualTo(Money.gbp("150.00"));
            assertThat(storedBalanceOf(ALICE))
                    .as("the new balance must survive a reload")
                    .isEqualTo(Money.gbp("150.00"));
        }

        @Test
        @DisplayName("appends exactly one entry to the account's ledger")
        void appendsOneEntry() {
            givenAccount(ALICE, "Ada Lovelace", "0");

            transactionService.deposit(ALICE, Money.gbp("50.00"), "Salary");

            assertThat(ledger.findByAccountId(ALICE))
                    .singleElement()
                    .satisfies(e -> {
                        assertThat(e.type()).isEqualTo(TransactionType.DEPOSIT);
                        assertThat(e.narrative()).isEqualTo("Salary");
                        assertThat(e.occurredAt()).isEqualTo(NOW);
                    });
        }

        @Test
        @DisplayName("refuses to deposit into an account that does not exist")
        void refusesUnknownAccount() {
            assertThatThrownBy(() -> transactionService.deposit(ALICE, Money.gbp("50.00"), null))
                    .isInstanceOf(AccountNotFoundException.class);
        }

        @Test
        @DisplayName("records nothing when the amount is invalid")
        void recordsNothingWhenAmountInvalid() {
            givenAccount(ALICE, "Ada Lovelace", "100.00");
            int entriesBefore = ledgerSizeOf(ALICE);

            assertThatThrownBy(() -> transactionService.deposit(ALICE, Money.zero(Money.GBP), null))
                    .isInstanceOf(InvalidAmountException.class);

            assertThat(storedBalanceOf(ALICE)).isEqualTo(Money.gbp("100.00"));
            assertThat(ledgerSizeOf(ALICE)).isEqualTo(entriesBefore);
        }
    }

    @Nested
    @DisplayName("when withdrawing")
    class Withdrawing {

        @Test
        @DisplayName("decreases the balance and persists it")
        void withdrawsAndPersists() {
            givenAccount(ALICE, "Ada Lovelace", "100.00");

            LedgerEntry entry = transactionService.withdraw(ALICE, Money.gbp("30.00"), "Cash machine");

            assertThat(entry.balanceAfter()).isEqualTo(Money.gbp("70.00"));
            assertThat(storedBalanceOf(ALICE)).isEqualTo(Money.gbp("70.00"));
        }

        @Test
        @DisplayName("allows an account to be emptied exactly")
        void allowsExactEmptying() {
            givenAccount(ALICE, "Ada Lovelace", "100.00");

            transactionService.withdraw(ALICE, Money.gbp("100.00"), null);

            assertThat(storedBalanceOf(ALICE)).isEqualTo(Money.zero(Money.GBP));
        }

        @Test
        @DisplayName("prevents an overdraft and leaves the stored balance untouched")
        void preventsOverdraft() {
            givenAccount(ALICE, "Ada Lovelace", "100.00");
            int entriesBefore = ledgerSizeOf(ALICE);

            assertThatThrownBy(() -> transactionService.withdraw(ALICE, Money.gbp("100.01"), null))
                    .isInstanceOf(InsufficientFundsException.class);

            assertThat(storedBalanceOf(ALICE)).isEqualTo(Money.gbp("100.00"));
            assertThat(ledgerSizeOf(ALICE))
                    .as("a refused withdrawal is not a transaction and must not appear on the statement")
                    .isEqualTo(entriesBefore);
        }

        @Test
        @DisplayName("refuses to withdraw from an account that does not exist")
        void refusesUnknownAccount() {
            assertThatThrownBy(() -> transactionService.withdraw(ALICE, Money.gbp("10.00"), null))
                    .isInstanceOf(AccountNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("when transferring")
    class Transferring {

        @Test
        @DisplayName("moves money out of the source and into the destination")
        void movesMoneyBetweenAccounts() {
            givenAccount(ALICE, "Ada Lovelace", "100.00");
            givenAccount(BOB, "Grace Hopper", "20.00");

            transactionService.transfer(ALICE, BOB, Money.gbp("30.00"), null);

            assertThat(storedBalanceOf(ALICE)).isEqualTo(Money.gbp("70.00"));
            assertThat(storedBalanceOf(BOB)).isEqualTo(Money.gbp("50.00"));
        }

        @Test
        @DisplayName("conserves money: the total across both accounts is unchanged")
        void conservesTotalMoney() {
            givenAccount(ALICE, "Ada Lovelace", "100.00");
            givenAccount(BOB, "Grace Hopper", "20.00");

            transactionService.transfer(ALICE, BOB, Money.gbp("30.00"), null);

            assertThat(storedBalanceOf(ALICE).plus(storedBalanceOf(BOB))).isEqualTo(Money.gbp("120.00"));
        }

        @Test
        @DisplayName("writes one entry per account, tied together by a shared reference")
        void writesBothLegsUnderOneReference() {
            givenAccount(ALICE, "Ada Lovelace", "100.00");
            givenAccount(BOB, "Grace Hopper", "0");

            TransferReceipt receipt = transactionService.transfer(ALICE, BOB, Money.gbp("30.00"), null);

            LedgerEntry debit = lastEntryOf(ALICE);
            LedgerEntry credit = lastEntryOf(BOB);

            assertThat(debit.type()).isEqualTo(TransactionType.TRANSFER_OUT);
            assertThat(credit.type()).isEqualTo(TransactionType.TRANSFER_IN);
            assertThat(debit.reference()).isEqualTo(credit.reference()).isEqualTo(receipt.reference());
            assertThat(receipt.amount()).isEqualTo(Money.gbp("30.00"));
            assertThat(receipt.sourceAccountId()).isEqualTo(ALICE);
            assertThat(receipt.destinationAccountId()).isEqualTo(BOB);
        }

        @Test
        @DisplayName("describes each leg from that account holder's point of view")
        void describesEachLegFromItsOwnPerspective() {
            givenAccount(ALICE, "Ada Lovelace", "100.00");
            givenAccount(BOB, "Grace Hopper", "0");

            transactionService.transfer(ALICE, BOB, Money.gbp("30.00"), null);

            assertThat(lastEntryOf(ALICE).narrative()).isEqualTo("Transfer to ACC-2002");
            assertThat(lastEntryOf(BOB).narrative()).isEqualTo("Transfer from ACC-1001");
        }

        @Test
        @DisplayName("prefers the caller's own narrative when one is supplied")
        void prefersSuppliedNarrative() {
            givenAccount(ALICE, "Ada Lovelace", "100.00");
            givenAccount(BOB, "Grace Hopper", "0");

            transactionService.transfer(ALICE, BOB, Money.gbp("30.00"), "Rent for April");

            assertThat(lastEntryOf(ALICE).narrative()).isEqualTo("Rent for April");
            assertThat(lastEntryOf(BOB).narrative()).isEqualTo("Rent for April");
        }

        @Test
        @DisplayName("refuses a transfer to the same account")
        void refusesSameAccount() {
            givenAccount(ALICE, "Ada Lovelace", "100.00");
            int entriesBefore = ledgerSizeOf(ALICE);

            assertThatThrownBy(() -> transactionService.transfer(ALICE, ALICE, Money.gbp("10.00"), null))
                    .isInstanceOf(SameAccountTransferException.class);

            assertThat(storedBalanceOf(ALICE)).isEqualTo(Money.gbp("100.00"));
            assertThat(ledgerSizeOf(ALICE)).isEqualTo(entriesBefore);
        }

        @Test
        @DisplayName("moves nothing at all when the source cannot cover the amount")
        void movesNothingWhenFundsShort() {
            givenAccount(ALICE, "Ada Lovelace", "10.00");
            givenAccount(BOB, "Grace Hopper", "20.00");
            int aliceEntriesBefore = ledgerSizeOf(ALICE);
            int bobEntriesBefore = ledgerSizeOf(BOB);

            assertThatThrownBy(() -> transactionService.transfer(ALICE, BOB, Money.gbp("50.00"), null))
                    .isInstanceOf(InsufficientFundsException.class);

            assertThat(storedBalanceOf(ALICE)).isEqualTo(Money.gbp("10.00"));
            assertThat(storedBalanceOf(BOB))
                    .as("the destination must never be credited for a transfer that failed")
                    .isEqualTo(Money.gbp("20.00"));
            assertThat(ledgerSizeOf(ALICE)).isEqualTo(aliceEntriesBefore);
            assertThat(ledgerSizeOf(BOB)).isEqualTo(bobEntriesBefore);
        }

        @Test
        @DisplayName("refuses when the source does not exist, leaving the destination untouched")
        void refusesUnknownSource() {
            givenAccount(BOB, "Grace Hopper", "20.00");
            int bobEntriesBefore = ledgerSizeOf(BOB);

            assertThatThrownBy(() -> transactionService.transfer(
                    AccountId.of("ACC-9999"), BOB, Money.gbp("10.00"), null))
                    .isInstanceOf(AccountNotFoundException.class);

            assertThat(storedBalanceOf(BOB)).isEqualTo(Money.gbp("20.00"));
            assertThat(ledgerSizeOf(BOB)).isEqualTo(bobEntriesBefore);
        }

        @Test
        @DisplayName("refuses when the destination does not exist, leaving the source untouched")
        void refusesUnknownDestination() {
            givenAccount(ALICE, "Ada Lovelace", "100.00");
            int aliceEntriesBefore = ledgerSizeOf(ALICE);

            assertThatThrownBy(() -> transactionService.transfer(
                    ALICE, AccountId.of("ACC-9999"), Money.gbp("10.00"), null))
                    .isInstanceOf(AccountNotFoundException.class);

            assertThat(storedBalanceOf(ALICE))
                    .as("the source must not be debited towards an account that does not exist")
                    .isEqualTo(Money.gbp("100.00"));
            assertThat(ledgerSizeOf(ALICE)).isEqualTo(aliceEntriesBefore);
        }

        @Test
        @DisplayName("behaves identically whichever way round the account ids sort")
        void behavesIdenticallyRegardlessOfIdOrdering() {
            // The service loads accounts in canonical id order to avoid deadlock, so both
            // directions must be exercised to cover that branch.
            givenAccount(ALICE, "Ada Lovelace", "100.00");
            givenAccount(BOB, "Grace Hopper", "100.00");

            transactionService.transfer(ALICE, BOB, Money.gbp("10.00"), null);
            transactionService.transfer(BOB, ALICE, Money.gbp("40.00"), null);

            assertThat(storedBalanceOf(ALICE)).isEqualTo(Money.gbp("130.00"));
            assertThat(storedBalanceOf(BOB)).isEqualTo(Money.gbp("70.00"));
        }

        @Test
        @DisplayName("refuses to transfer an amount in another currency")
        void refusesForeignCurrency() {
            givenAccount(ALICE, "Ada Lovelace", "100.00");
            givenAccount(BOB, "Grace Hopper", "0");

            assertThatThrownBy(() -> transactionService.transfer(ALICE, BOB, Money.of("10.00", USD), null))
                    .isInstanceOf(com.natwest.ledger.domain.CurrencyMismatchException.class);

            assertThat(storedBalanceOf(ALICE)).isEqualTo(Money.gbp("100.00"));
        }
    }

    @Nested
    @DisplayName("across a sequence of operations")
    class Reconciliation {

        @Test
        @DisplayName("the ledger always replays to the stored balance")
        void ledgerReplaysToStoredBalance() {
            givenAccount(ALICE, "Ada Lovelace", "500.00");
            givenAccount(BOB, "Grace Hopper", "0");

            transactionService.deposit(ALICE, Money.gbp("125.50"), null);
            transactionService.withdraw(ALICE, Money.gbp("40.25"), null);
            transactionService.transfer(ALICE, BOB, Money.gbp("200.00"), null);
            transactionService.transfer(BOB, ALICE, Money.gbp("15.75"), null);

            assertLedgerReconciles(ALICE);
            assertLedgerReconciles(BOB);

            assertThat(storedBalanceOf(ALICE)).isEqualTo(Money.gbp("401.00"));
            assertThat(storedBalanceOf(BOB)).isEqualTo(Money.gbp("184.25"));
        }

        @Test
        @DisplayName("history is returned oldest first, the order a statement reads in")
        void historyIsChronological() {
            givenAccount(ALICE, "Ada Lovelace", "100.00");
            transactionService.deposit(ALICE, Money.gbp("10.00"), "second");
            transactionService.withdraw(ALICE, Money.gbp("5.00"), "third");

            List<LedgerEntry> history = accountService.transactionHistory(ALICE);

            assertThat(history).extracting(LedgerEntry::narrative)
                    .containsExactly("Opening balance", "second", "third");
            assertThat(history).extracting(LedgerEntry::balanceAfter)
                    .containsExactly(Money.gbp("100.00"), Money.gbp("110.00"), Money.gbp("105.00"));
        }

        private void assertLedgerReconciles(AccountId accountId) {
            Money replayed = ledger.findByAccountId(accountId).stream()
                    .map(LedgerEntry::signedAmount)
                    .reduce(Money.zero(Money.GBP), Money::plus);

            assertThat(replayed)
                    .as("replaying the ledger of %s must reproduce its balance", accountId)
                    .isEqualTo(storedBalanceOf(accountId));
        }
    }
}
