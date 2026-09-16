package com.natwest.ledger.service;

import com.natwest.ledger.exception.AccountNotFoundException;
import com.natwest.ledger.exception.DuplicateAccountException;
import com.natwest.ledger.exception.ErrorCode;
import com.natwest.ledger.exception.InvalidAmountException;
import com.natwest.ledger.model.Account;
import com.natwest.ledger.model.AccountId;
import com.natwest.ledger.model.LedgerEntry;
import com.natwest.ledger.model.Money;
import com.natwest.ledger.model.TransactionType;
import com.natwest.ledger.repository.memory.InMemoryAccountRepository;
import com.natwest.ledger.repository.memory.InMemoryLedgerRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Specifies account lifecycle and the two query APIs the requirements ask for: balance and
 * transaction history.
 *
 * <p>Wires the real in-memory adapters rather than mocks. The interesting assertions are about what
 * is actually stored afterwards, and a mock can only confirm that a method was called - which is
 * not the same thing as the data being correct.
 */
@DisplayName("The account service")
class AccountServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-20T10:15:30Z");
    private static final AccountId ACC_1 = AccountId.of("ACC-1001");

    private InMemoryAccountRepository accounts;
    private InMemoryLedgerRepository ledger;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accounts = new InMemoryAccountRepository();
        ledger = new InMemoryLedgerRepository();
        accountService = new AccountService(accounts, ledger, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Nested
    @DisplayName("when opening an account")
    class Opening {

        @Test
        @DisplayName("stores an empty account with no history")
        void opensEmptyAccount() {
            Account opened = accountService.openAccount(ACC_1, "Ada Lovelace", Money.zero(Money.GBP));

            assertThat(opened.id()).isEqualTo(ACC_1);
            assertThat(opened.holderName()).isEqualTo("Ada Lovelace");
            assertThat(opened.balance()).isEqualTo(Money.zero(Money.GBP));
            assertThat(accounts.findById(ACC_1)).isPresent();
            assertThat(ledger.findByAccountId(ACC_1))
                    .as("an account opened empty has nothing to explain")
                    .isEmpty();
        }

        @Test
        @DisplayName("records an opening balance as a real deposit, so the ledger explains every penny")
        void recordsOpeningBalanceAsLedgerEntry() {
            accountService.openAccount(ACC_1, "Ada Lovelace", Money.gbp("250.00"));

            List<LedgerEntry> history = ledger.findByAccountId(ACC_1);

            assertThat(history).hasSize(1);
            assertThat(history.get(0).type()).isEqualTo(TransactionType.DEPOSIT);
            assertThat(history.get(0).amount()).isEqualTo(Money.gbp("250.00"));
            assertThat(history.get(0).balanceAfter()).isEqualTo(Money.gbp("250.00"));
            assertThat(history.get(0).narrative()).isEqualTo("Opening balance");
            assertThat(accountService.balanceOf(ACC_1)).isEqualTo(Money.gbp("250.00"));
        }

        @Test
        @DisplayName("stamps the opening entry with the injected clock, not the wall clock")
        void usesInjectedClock() {
            accountService.openAccount(ACC_1, "Ada Lovelace", Money.gbp("10.00"));

            assertThat(ledger.findByAccountId(ACC_1).get(0).occurredAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("refuses a second account with the same id")
        void refusesDuplicateId() {
            accountService.openAccount(ACC_1, "Ada Lovelace", Money.gbp("10.00"));

            DuplicateAccountException thrown = assertThrows(DuplicateAccountException.class,
                    () -> accountService.openAccount(ACC_1, "Someone Else", Money.gbp("10.00")));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.DUPLICATE_ACCOUNT);
            assertThat(thrown.details()).containsEntry("accountId", "ACC-1001");
        }

        @Test
        @DisplayName("treats a differently-cased id as the same account, so it cannot be duplicated")
        void refusesDuplicateIdIgnoringCase() {
            accountService.openAccount(AccountId.of("ACC-1001"), "Ada Lovelace", Money.gbp("10.00"));

            assertThatThrownBy(() -> accountService.openAccount(
                    AccountId.of("acc-1001"), "Someone Else", Money.gbp("10.00")))
                    .isInstanceOf(DuplicateAccountException.class);
        }

        @Test
        @DisplayName("refuses to open an account already in overdraft")
        void refusesNegativeOpeningBalance() {
            assertThatThrownBy(() -> accountService.openAccount(ACC_1, "Ada Lovelace", Money.gbp("-0.01")))
                    .isInstanceOf(InvalidAmountException.class)
                    .hasMessageContaining("Opening balance cannot be negative");

            assertThat(accounts.findById(ACC_1))
                    .as("a rejected opening must not leave an account behind")
                    .isEmpty();
        }

        @Test
        @DisplayName("leaves nothing behind when the holder name is missing")
        void refusesBlankHolderName() {
            assertThatThrownBy(() -> accountService.openAccount(ACC_1, " ", Money.gbp("10.00")))
                    .isInstanceOf(IllegalArgumentException.class);

            assertThat(accounts.findById(ACC_1)).isEmpty();
            assertThat(ledger.findByAccountId(ACC_1)).isEmpty();
        }
    }

    @Nested
    @DisplayName("when queried for a balance")
    class BalanceQuery {

        @Test
        @DisplayName("reports the current balance")
        void reportsBalance() {
            accountService.openAccount(ACC_1, "Ada Lovelace", Money.gbp("75.25"));

            assertThat(accountService.balanceOf(ACC_1)).isEqualTo(Money.gbp("75.25"));
        }

        @Test
        @DisplayName("says so plainly when the account does not exist")
        void reportsUnknownAccount() {
            AccountNotFoundException thrown = assertThrows(AccountNotFoundException.class,
                    () -> accountService.balanceOf(AccountId.of("ACC-9999")));

            assertThat(thrown.errorCode()).isEqualTo(ErrorCode.ACCOUNT_NOT_FOUND);
            assertThat(thrown.details()).containsEntry("accountId", "ACC-9999");
        }
    }

    @Nested
    @DisplayName("when queried for transaction history")
    class HistoryQuery {

        @Test
        @DisplayName("returns an empty statement for an account that has done nothing")
        void returnsEmptyHistoryForUntouchedAccount() {
            accountService.openAccount(ACC_1, "Ada Lovelace", Money.zero(Money.GBP));

            assertThat(accountService.transactionHistory(ACC_1)).isEmpty();
        }

        @Test
        @DisplayName("distinguishes an unknown account from one with no transactions")
        void distinguishesUnknownAccountFromEmptyHistory() {
            // Returning an empty list for both would silently swallow a mistyped account id.
            assertThatThrownBy(() -> accountService.transactionHistory(AccountId.of("ACC-9999")))
                    .isInstanceOf(AccountNotFoundException.class);
        }
    }

    @Test
    @DisplayName("reports an unknown account when asked to load one")
    void findAccountRejectsUnknownAccount() {
        assertThatThrownBy(() -> accountService.findAccount(AccountId.of("ACC-9999")))
                .isInstanceOf(AccountNotFoundException.class);
    }
}

