package com.dunghaiquyen.ecommerce.modules.payment.entity;

public enum BankTransactionMatchStatus {
    RECEIVED,
    MATCHED,
    UNMATCHED,
    AMOUNT_MISMATCH,
    AMBIGUOUS,
    LATE_PAYMENT,
    IGNORED_OUTGOING,
    MANUAL_REVIEW,
    MANUALLY_MATCHED
}
