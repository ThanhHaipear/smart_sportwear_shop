package com.dunghaiquyen.ecommerce.modules.returns.entity;

/** Exact set V3's chk_return_items_resolution check constraint allows. Only REFUND feeds a Refund's amount - see ReturnService. */
public enum ReturnItemResolution {
    REFUND,
    REJECT
}
