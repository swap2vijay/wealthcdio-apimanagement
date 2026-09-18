package com.natwest.ledger.model;


/**
 * The business meaning of a ledger entry.
 *
 * <p>A transfer deliberately produces <em>two</em> entries, {@link #TRANSFER_OUT} on the source
 * and {@link #TRANSFER_IN} on the destination, rather than one shared row. Each account's ledger
 * then stands alone as a complete and readable statement, which is what
 * "a ledger of transactions per account" asks for. The two legs are tied together by a shared
 * {@link TransactionReference}, so the whole movement can still be reconstructed.
 */
public enum TransactionType {

    DEPOSIT(Direction.CREDIT),

    WITHDRAWAL(Direction.DEBIT),

    /** The debit leg of a transfer, written against the source account. */
    TRANSFER_OUT(Direction.DEBIT),

    /** The credit leg of a transfer, written against the destination account. */
    TRANSFER_IN(Direction.CREDIT);

    private final Direction direction;

    TransactionType(Direction direction) {
        this.direction = direction;
    }

    public Direction direction() {
        return direction;
    }

    public boolean isCredit() {
        return direction == Direction.CREDIT;
    }

    public boolean isDebit() {
        return direction == Direction.DEBIT;
    }
}
