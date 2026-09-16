package com.natwest.ledger.dto;

import com.natwest.ledger.model.AccountId;
import com.natwest.ledger.service.Statement;

import java.util.List;

/**
 * A page of an account's statement.
 *
 * <p>An object wrapping the transactions rather than a bare JSON array. A top-level array leaves
 * nowhere to put paging metadata, so a client cannot tell a full page from the final one without
 * making another request that returns nothing. It also cannot be extended later without breaking
 * every consumer, whereas adding a field to an object is backwards compatible.
 *
 * <p>{@code hasNext} is included even though it is derivable from page, size and total. It is the
 * question every paging client actually asks, and computing it here once is better than having each
 * client reimplement the arithmetic and one of them get the boundary wrong.
 */
public record StatementResponse(
        String accountId,
        List<TransactionResponse> transactions,
        int page,
        int size,
        long totalTransactions,
        int totalPages,
        boolean hasNext) {

    public static StatementResponse from(AccountId accountId, Statement statement) {
        return new StatementResponse(
                accountId.value(),
                TransactionResponse.from(statement.entries()),
                statement.page(),
                statement.size(),
                statement.totalEntries(),
                statement.totalPages(),
                statement.hasNext());
    }
}

