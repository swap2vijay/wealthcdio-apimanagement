package com.natwest.ledger.exception;

import com.natwest.ledger.model.Account;
import com.natwest.ledger.model.AccountId;
import com.natwest.ledger.model.Money;
import com.natwest.ledger.model.TransactionReference;

import java.util.Map;

/**
 * Raised when a transfer failed and the compensating reversal <em>also</em> failed.
 *
 * <p>This is the honest admission of a saga's fundamental weakness. A saga replaces a single atomic
 * transaction with a sequence of committed steps plus instructions for undoing them - and that only
 * holds while the undo succeeds. When it does not, an account has been debited and the money is
 * nowhere: not with the payer, not with the payee.
 *
 * <p>Reported as 500 rather than 503, and explicitly {@code retryable: false}. A retry would debit the
 * account a second time while the first debit is still stranded, turning one stuck transfer into two.
 * The reference is included so the affected entries can be found in both accounts' ledgers.
 *
 * <p>A production system would not rely on the caller noticing this: it would persist saga state and
 * run a recovery process that retries outstanding compensations until they succeed. That is noted as a
 * known limitation in the README rather than pretended away here.
 */
public class TransferCompensationFailedException extends LedgerException {

    public TransferCompensationFailedException(TransactionReference reference,
                                              AccountId sourceAccountId,
                                              Money amount,
                                              String originalFailure) {
        super(ErrorCode.TRANSFER_COMPENSATION_FAILED,
                ("Transfer %s failed (%s) and the reversal of %s from account %s did not succeed. "
                        + "The funds have left the account and have not arrived anywhere. "
                        + "This requires manual reconciliation; do not retry the transfer.")
                        .formatted(reference.value(), originalFailure, amount, sourceAccountId.value()),
                Map.of("reference", reference.value(),
                        "accountId", sourceAccountId.value(),
                        "amount", amount.toPlainString(),
                        "originalFailure", originalFailure,
                        "retryable", false,
                        "requiresManualReconciliation", true));
    }
}

