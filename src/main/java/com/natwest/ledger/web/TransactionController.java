package com.natwest.ledger.web;

import com.natwest.ledger.application.TransactionService;
import com.natwest.ledger.config.LedgerProperties;
import com.natwest.ledger.domain.AccountId;
import com.natwest.ledger.domain.Money;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Money movement: deposits, withdrawals and transfers.
 *
 * <p><b>Modelled as resources, not verbs.</b> The endpoints are
 * {@code POST /accounts/{id}/deposits} rather than {@code POST /accounts/{id}/deposit}, because each
 * call appends a new deposit to a collection of them. That is also why the direction of the movement
 * lives in the URL instead of a {@code type} field in the body: a caller cannot accidentally post a
 * withdrawal to the deposit endpoint, and the controller never has to disambiguate.
 *
 * <p>All three respond {@code 201 Created}. Each one appends an immutable ledger entry - a new fact
 * that did not exist before - so {@code 200 OK} would understate what happened.
 *
 * <p>A transfer is not nested under either account. It acts on both, and putting one of them in the
 * path would imply an ownership that does not exist.
 */
@RestController
@RequestMapping("/api/v1")
public class TransactionController {

    private final TransactionService transactionService;
    private final LedgerProperties properties;

    public TransactionController(TransactionService transactionService, LedgerProperties properties) {
        this.transactionService = transactionService;
        this.properties = properties;
    }

    /** Pays money into an account. */
    @PostMapping("/accounts/{accountId}/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse deposit(@PathVariable String accountId,
                                       @Valid @RequestBody MoneyMovementRequest request) {
        return TransactionResponse.from(transactionService.deposit(
                AccountId.of(accountId), amountOf(request), request.narrative()));
    }

    /**
     * Takes money out of an account.
     *
     * <p>Returns {@code 422 Unprocessable Entity} when the balance cannot cover it - the request was
     * understood perfectly, the account simply cannot honour it.
     */
    @PostMapping("/accounts/{accountId}/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse withdraw(@PathVariable String accountId,
                                        @Valid @RequestBody MoneyMovementRequest request) {
        return TransactionResponse.from(transactionService.withdraw(
                AccountId.of(accountId), amountOf(request), request.narrative()));
    }

    /** Moves money from one account to another. */
    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse transfer(@Valid @RequestBody TransferRequest request) {
        Money amount = MoneyRequests.toMoney(request.amount(), request.currency(), properties.currency());

        return TransferResponse.from(transactionService.transfer(
                AccountId.of(request.sourceAccountId()),
                AccountId.of(request.destinationAccountId()),
                amount,
                request.narrative()));
    }

    private Money amountOf(MoneyMovementRequest request) {
        return MoneyRequests.toMoney(request.amount(), request.currency(), properties.currency());
    }
}
