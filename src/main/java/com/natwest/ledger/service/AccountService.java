package com.natwest.ledger.service;

import com.natwest.ledger.exception.AccountNotFoundException;
import com.natwest.ledger.exception.DuplicateAccountException;
import com.natwest.ledger.exception.InvalidAmountException;
import com.natwest.ledger.model.Account;
import com.natwest.ledger.model.AccountId;
import com.natwest.ledger.model.LedgerEntry;
import com.natwest.ledger.model.Money;
import com.natwest.ledger.model.TransactionReference;
import com.natwest.ledger.repository.AccountRepository;
import com.natwest.ledger.repository.LedgerRepository;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Opening accounts and answering questions about them.
 *
 * <p>Holds no business rules of its own. Its job is to load the right aggregate, ask it to do the
 * work, and persist the result - the arithmetic and the invariants stay in {@link Account}. A
 * service that starts computing balances itself is a sign the domain model has been bypassed.
 *
 * <p>Takes a {@link Clock} rather than calling {@code Instant.now()}, so tests can pin time and
 * assert on exact timestamps.
 */
@Service
public class AccountService {

    private static final Logger log = LogManager.getLogger(AccountService.class);

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final Clock clock;

    public AccountService(AccountRepository accounts, LedgerRepository ledger, Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.clock = clock;
    }

    /**
     * Opens an account, funding it if an opening balance was supplied.
     *
     * <p><b>The account is always opened empty and then funded by a deposit</b>, even when the
     * caller supplies an opening balance. Setting the balance directly would leave a figure that no
     * ledger entry explains, breaking the property that replaying an account's ledger reproduces its
     * balance. Paying the money in means the statement accounts for every penny from the first line.
     *
     * <p>Transactional so the account row and its opening ledger entry commit together. Otherwise a
     * crash between the two would leave an account holding money that no entry explains.
     *
     * @throws DuplicateAccountException if the identifier is already in use
     * @throws InvalidAmountException    if the opening balance is negative
     */
    @Transactional
    public Account openAccount(AccountId accountId, String holderName, Money openingBalance) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(openingBalance, "openingBalance");

        if (openingBalance.isNegative()) {
            throw new InvalidAmountException(
                    "Opening balance cannot be negative but was %s".formatted(openingBalance),
                    Map.of("openingBalance", openingBalance.toPlainString()));
        }
        if (accounts.existsById(accountId)) {
            throw new DuplicateAccountException(accountId);
        }

        Account account = Account.open(accountId, holderName, Money.zero(openingBalance.currency()));

        if (openingBalance.isPositive()) {
            LedgerEntry opening = account.deposit(
                    openingBalance, TransactionReference.newReference(), clock.instant(), "Opening balance");
            ledger.append(opening);
        }

        Account saved = accounts.save(account);
        log.info("Opened account {} for '{}' with balance {}", accountId, saved.holderName(), saved.balance());
        return saved;
    }

    /**
     * Loads an account.
     *
     * @throws AccountNotFoundException if no such account exists
     */
    @Transactional(readOnly = true)
    public Account findAccount(AccountId accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    /**
     * The current balance of an account.
     *
     * @throws AccountNotFoundException if no such account exists
     */
    @Transactional(readOnly = true)
    public Money balanceOf(AccountId accountId) {
        return findAccount(accountId).balance();
    }

    /**
     * An account's transaction history, oldest first.
     *
     * <p>Deliberately distinguishes "no such account" from "an account with no transactions": the
     * former is a client error, the latter an empty but perfectly valid statement. Returning an
     * empty list for both would hide typos in account identifiers.
     *
     * @throws AccountNotFoundException if no such account exists
     */
    @Transactional(readOnly = true)
    public List<LedgerEntry> transactionHistory(AccountId accountId) {
        Objects.requireNonNull(accountId, "accountId");
        requireExists(accountId);
        return ledger.findByAccountId(accountId);
    }

    /**
     * One page of an account's transaction history, oldest first.
     *
     * <p>The paged form is what the API uses. An account's history only grows, so an endpoint that
     * returns all of it has a response size chosen by the customer rather than by this service.
     *
     * <p>An out-of-range page returns an empty window rather than failing: asking for page 40 of a
     * 3-page statement is a reasonable thing for a paging client to do at a boundary, and it is not an
     * error. A malformed page or size <em>is</em> rejected, because that is a caller defect.
     *
     * @param page zero-based
     * @param size clamped to {@link Statement#MAX_PAGE_SIZE}
     * @throws AccountNotFoundException if no such account exists
     * @throws IllegalArgumentException if page is negative or size is not positive
     */
    @Transactional(readOnly = true)
    public Statement transactionHistory(AccountId accountId, int page, int size) {
        Objects.requireNonNull(accountId, "accountId");
        if (page < 0) {
            throw new IllegalArgumentException("page must not be negative but was " + page);
        }
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive but was " + size);
        }
        requireExists(accountId);

        int effectiveSize = Math.min(size, Statement.MAX_PAGE_SIZE);
        List<LedgerEntry> entries = ledger.findByAccountId(accountId, page * effectiveSize, effectiveSize);

        return new Statement(entries, page, effectiveSize, ledger.countByAccountId(accountId));
    }

    private void requireExists(AccountId accountId) {
        if (!accounts.existsById(accountId)) {
            throw new AccountNotFoundException(accountId);
        }
    }
}

