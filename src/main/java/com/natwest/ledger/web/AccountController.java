package com.natwest.ledger.web;

import com.natwest.ledger.application.AccountService;
import com.natwest.ledger.config.LedgerProperties;
import com.natwest.ledger.domain.Account;
import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.Money;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.util.List;

/**
 * Accounts, and the two queries the requirements ask for: balance and transaction history.
 *
 * <p>Thin by design. Each method converts a request into domain types, calls one application
 * service method, and maps the result to a response. There is no {@code if} here about money,
 * because every such decision belongs to the domain - a controller that starts making business
 * decisions is one that cannot be tested without HTTP.
 *
 * <p>Error handling is absent for the same reason: failures propagate as
 * {@link com.natwest.ledger.error.LedgerException}s and are turned into responses centrally by
 * {@link GlobalExceptionHandler}. Try/catch in every method would be duplication that eventually
 * becomes inconsistent.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;
    private final LedgerProperties properties;
    private final Clock clock;

    public AccountController(AccountService accountService, LedgerProperties properties, Clock clock) {
        this.accountService = accountService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Opens an account.
     *
     * <p>Responds {@code 201 Created} with a {@code Location} header, because this creates a
     * resource at a URL the client can then fetch. Returns the account body as well so a caller does
     * not need a second round trip to learn the balance it was opened with.
     *
     * <p>A repeated request with the same id returns {@code 409 Conflict} rather than quietly
     * succeeding: account creation is not idempotent here, and pretending otherwise would hide a
     * client defect.
     */
    @PostMapping
    public ResponseEntity<AccountResponse> openAccount(@Valid @RequestBody OpenAccountRequest request) {
        Money openingBalance = MoneyRequests.toMoney(
                request.openingBalance() == null ? BigDecimal.ZERO : request.openingBalance(),
                request.currency(),
                properties.currency());

        Account account = accountService.openAccount(
                AccountId.of(request.accountId()), request.holderName(), openingBalance);

        return ResponseEntity
                .created(URI.create("/api/v1/accounts/" + account.id().value()))
                .body(AccountResponse.from(account));
    }

    /** The account and its current balance. */
    @GetMapping("/{accountId}")
    public AccountResponse getAccount(@PathVariable String accountId) {
        return AccountResponse.from(accountService.findAccount(AccountId.of(accountId)));
    }

    /**
     * The balance alone.
     *
     * <p>Kept as its own endpoint because a balance check is by far the most frequent read and the
     * cheapest to serve; giving it a dedicated URL means it can be cached or rate-limited
     * independently of the fuller account representation.
     */
    @GetMapping("/{accountId}/balance")
    public BalanceResponse getBalance(@PathVariable String accountId) {
        AccountId id = AccountId.of(accountId);
        return BalanceResponse.of(id, accountService.balanceOf(id), clock.instant());
    }

    /**
     * The account's transaction history, oldest first.
     *
     * <p>Returns the full history. Unpaged is a known limitation: it is honest for the current
     * in-memory store, and paging is deferred to Phase 4 where it can be pushed down into a database
     * query rather than faked by slicing a list that was already loaded in full.
     *
     * <p>An unknown account gives {@code 404}, not an empty list, so a mistyped id is not mistaken
     * for a customer who has never transacted.
     */
    @GetMapping("/{accountId}/transactions")
    public List<TransactionResponse> getTransactions(@PathVariable String accountId) {
        return TransactionResponse.from(accountService.transactionHistory(AccountId.of(accountId)));
    }
}
