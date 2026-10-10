package com.dunghaiquyen.ecommerce.modules.payment.entity;

/**
 * Shared by Order.paymentStatus and Payment.status. Order/payment status are
 * deliberately separate state machines from OrderStatus (see ERD_PHASE1.md 9.3).
 */
public enum PaymentStatus {
    UNPAID,
    PENDING,
    PAID,
    FAILED,
    CANCELLED,
    PARTIALLY_REFUNDED,
    REFUNDED
}
