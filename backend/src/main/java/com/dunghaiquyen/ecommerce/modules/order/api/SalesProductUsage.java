package com.dunghaiquyen.ecommerce.modules.order.api;

import java.util.List;
import java.util.UUID;

/** Sales-owned operations used by Catalog before deleting product variants. */
public interface SalesProductUsage {

    boolean hasOrderHistory(List<UUID> variantIds);

    void deleteCartItems(List<UUID> variantIds);
}
