package com.natwest.ledger.application;

import com.natwest.ledger.domain.LedgerEntry;

import java.util.List;

/**
 * One page of an account's transaction history, with enough context to fetch the rest.
 *
 * <p>Carries the total count as well as the entries. Without it a client cannot tell whether a full
 * page means "that is everything" or "there is more" except by requesting another page and getting
 * nothing back - which costs a round trip on every statement view.
 *
 * @param entries      the entries in this window, oldest first
 * @param page         zero-based page number
 * @param size         the requested page size, which the last page may not fill
 * @param totalEntries how many entries the account has in total
 */
public record Statement(List<LedgerEntry> entries, int page, int size, long totalEntries) {

    /** The default page size: generous enough for a typical statement view, bounded enough to be safe. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * The largest page a caller may ask for.
     *
     * <p>A cap exists because otherwise {@code ?size=1000000} makes the caller the one who decides how
     * much memory this service allocates.
     */
    public static final int MAX_PAGE_SIZE = 200;

    public Statement {
        entries = List.copyOf(entries);
    }

    public int totalPages() {
        return size <= 0 ? 0 : (int) Math.ceil((double) totalEntries / size);
    }

    public boolean hasNext() {
        return (long) (page + 1) * size < totalEntries;
    }
}
