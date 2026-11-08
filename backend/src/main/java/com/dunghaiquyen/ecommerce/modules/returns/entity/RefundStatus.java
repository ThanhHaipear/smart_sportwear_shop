package com.dunghaiquyen.ecommerce.modules.returns.entity;

/**
 * Exact set V3's chk_refunds_status check constraint allows. This phase only
 * ever produces PENDING (on creation) then COMPLETED/FAILED/CANCELLED (admin
 * PATCH) - PROCESSING is also used while an outbound bank transfer is awaiting
 * confirmation from the SePay transaction webhook.
 */
public enum RefundStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED,
    CANCELLED
}
