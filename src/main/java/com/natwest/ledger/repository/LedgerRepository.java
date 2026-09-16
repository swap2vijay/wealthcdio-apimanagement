package com.natwest.ledger.repository;

import com.natwest.ledger.model.AccountId;
import com.natwest.ledger.model.LedgerEntry;

import java.util.List;

/**
 * The append-only store of ledger entries.
 *
 * <p>Offers no update or delete. That is the point: a ledger is a record of what happened, and the
 * absence of those operations means a mistake can only ever be corrected by appending a
 * compensating entry, never by rewriting history. Making the interface incapable of the wrong
 * thing is more reliable than a policy asking people not to do it.
 */
public interface LedgerRepository {

    /** Appends a single entry. */
    LedgerEntry append(LedgerEntry entry);

    /**
     * Appends several entries as one unit.
     *
     * <p>Exists for transfers, which produce a debit and a credit that must both be recorded or
     * neither: writing them one at a time leaves a window in which the ledger shows money leaving
     * one account and never arriving at the other.
     */
    List<LedgerEntry> appendAll(List<LedgerEntry> entries);

    /**
     * All entries for an account, oldest first.
     *
     * <p>Chronological because that is how a statement reads. Unbounded, so it is for internal use -
     * reconciliation, which genuinely needs every entry. Anything serving a caller should use
     * {@link #findByAccountId(AccountId, int, int)}.
     */
    List<LedgerEntry> findByAccountId(AccountId accountId);

    /**
     * A window of an account's entries, oldest first.
     *
     * <p>Exists because an account's history only ever grows. An endpoint that returns all of it has
     * a response size set by the customer's transaction count, which is fine in a test and a
     * liability in production. Paging is expressed as offset and limit rather than as a Spring Data
     * {@code Pageable} to keep the framework out of a port the domain side depends on.
     *
     * <p>Ordering must be total and stable, not merely by timestamp: transfer legs share an instant,
     * so a tie-break is required or two reads can disagree. The JPA adapter orders by an
     * insertion sequence for exactly this reason.
     *
     * @param offset how many entries to skip, zero-based
     * @param limit  the maximum number to return
     */
    List<LedgerEntry> findByAccountId(AccountId accountId, int offset, int limit);

    /** How many entries an account has, so a caller can tell whether more pages exist. */
    long countByAccountId(AccountId accountId);
}

