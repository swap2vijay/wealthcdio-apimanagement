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
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/**
 * Moving money: deposits, withdrawals and transfers.
 *
 * <p>Every operation follows the same shape - load the account, let the account decide, record what
 * it decided. The validation lives in {@link Account}, so this class contains no {@code if} guarding
 * a balance.
 *
 * <p><b>Deposits and withdrawals are single, atomic transactions.</b> Each touches one account, so
 * {@code @Transactional} gives "both the balance and its ledger entry, or neither" for free. They need
 * no compliance screening, which is why an outage in that dependency does not stop them.
 *
 * <p><b>A transfer is not.</b> It spans a call to the compliance service, so it cannot be one database
 * transaction and is delegated to {@link TransferSaga}, which commits each step separately and
 * compensates when a later one fails. That asymmetry is the honest shape of the problem rather than an
 * inconsistency: only the transfer crosses a process boundary.
 *
 * <p><b>The transaction boundary is also what makes optimistic locking work.</b> The JPA adapter
 * detects a concurrent change by comparing the version it read at load time. If load and save sat in
 * separate transactions there would be no version to compare, and two simultaneous withdrawals could
 * each overwrite the other's result - so these annotations are load-bearing, not decoration.
 */
@Service
public class TransactionService {

    private static final Logger log = LogManager.getLogger(TransactionService.class);

    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final TransferSaga transferSaga;
    private final Clock clock;

    public TransactionService(AccountRepository accounts,
                              LedgerRepository ledger,
                              TransferSaga transferSaga,
                              Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.transferSaga = transferSaga;
        this.clock = clock;
    }

    /**
     * Pays money into an account.
     *
     * @throws AccountNotFoundException if no such account exists
     * @throws com.natwest.ledger.domain.InvalidAmountException     if the amount is not positive
     * @throws com.natwest.ledger.domain.CurrencyMismatchException  if the currency differs
     */
    @Transactional
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
    @Transactional
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
     * Moves money between two accounts, screening it with compliance on the way.
     *
     * <p>Delegates to {@link TransferSaga}. <b>Deliberately not {@code @Transactional}</b>: a transfer
     * now spans a call to another service, so it is a saga of separately committed steps rather than one
     * atomic unit. A transaction here would wrap the whole sequence and defeat that - the debit would no
     * longer be durable before the remote call, and compensation would be redundant because a rollback
     * would already have undone it. See {@link TransferSaga} for the full reasoning.
     *
     * <p>Kept on this service so that the three money-movement operations still read as one API to the
     * controller, even though one of them is now considerably more involved than the others.
     *
     * @throws SameAccountTransferException           source and destination are the same account
     * @throws AccountNotFoundException               either account does not exist
     * @throws InsufficientFundsException             the source cannot cover the amount
     * @throws ComplianceRejectedException            compliance refused it; the debit has been reversed
     * @throws ComplianceUnavailableException         compliance was unreachable; the debit has been reversed
     * @throws TransferCompensationFailedException    the reversal also failed; needs reconciliation
     */
    public TransferReceipt transfer(AccountId sourceId, AccountId destinationId, Money amount, String narrative) {
        return transferSaga.execute(sourceId, destinationId, amount, narrative);
    }

    private Account load(AccountId accountId) {
        Objects.requireNonNull(accountId, "accountId");
        return accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
