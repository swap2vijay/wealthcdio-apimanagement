package com.natwest.ledger.model;

/**
 * Which way money moved relative to the account holding the ledger entry.
 *
 * <p>Named from the account's point of view: a {@code CREDIT} increases its balance. Every entry
 * stores its amount as a positive magnitude plus a direction, rather than as a signed amount,
 * because a ledger that mixes signs invites the classic defect of adding a debit instead of
 * subtracting it.
 */
public enum Direction {

    /** Money into the account; increases the balance. */
    CREDIT,

    /** Money out of the account; decreases the balance. */
    DEBIT
}

