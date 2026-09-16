package com.natwest.ledger.controller;

import com.natwest.ledger.config.LedgerProperties;
import com.natwest.ledger.dto.AccountResponse;
import com.natwest.ledger.dto.BalanceResponse;
import com.natwest.ledger.dto.MoneyRequests;
import com.natwest.ledger.dto.OpenAccountRequest;
import com.natwest.ledger.dto.StatementResponse;
import com.natwest.ledger.exception.GlobalExceptionHandler;
import com.natwest.ledger.exception.LedgerException;
import com.natwest.ledger.model.Account;
import com.natwest.ledger.model.AccountId;
import com.natwest.ledger.model.Money;
import com.natwest.ledger.service.AccountService;
import com.natwest.ledger.service.Statement;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;

/**
 * Accounts, and the two queries the requirements ask for: balance and transaction history.
 *
 * <p>Thin by design. Each method converts a request into domain types, calls one application
 * service method, and maps the result to a response. There is no {@code if} here about money,
 * because every such decision belongs to the domain - a controller that starts making business
 * decisions is one that cannot be tested without HTTP.
 *
 * <p>Error handling is absent for the same reason: failures propagate as
 * {@link LedgerException}s and are turned into responses centrally by
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
     * A page of the account's transaction history, oldest first.
     *
     * <p>Paged rather than complete. An account's history only ever grows, so an unpaged endpoint has a
     * response size decided by the customer's transaction count rather than by this service - fine in a
     * test, a liability in production. The page size is capped server-side, so {@code ?size=1000000}
     * cannot make a caller the one who chooses how much memory is allocated.
     *
     * <p>An unknown account gives {@code 404}, not an empty page, so a mistyped id is not mistaken for a
     * customer who has never transacted. An out-of-range page, by contrast, returns an empty window:
     * that is a normal thing for a paging client to do at a boundary, not an error.
     *
     * @param page zero-based, defaults to the first page
     * @param size defaults to {@link Statement#DEFAULT_PAGE_SIZE}, capped at {@link Statement#MAX_PAGE_SIZE}
     */
    @GetMapping("/{accountId}/transactions")
    public StatementResponse getTransactions(@PathVariable String accountId,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        AccountId id = AccountId.of(accountId);

        // Defaults are resolved here rather than in a defaultValue string so the constants in
        // Statement remain the single source of truth.
        Statement statement = accountService.transactionHistory(
                id,
                page == null ? 0 : page,
                size == null ? Statement.DEFAULT_PAGE_SIZE : size);

        return StatementResponse.from(id, statement);
    }
}

