package com.dunghaiquyen.ecommerce.modules.inventory.repository;

import com.dunghaiquyen.ecommerce.modules.inventory.entity.InventoryTransaction;
import com.dunghaiquyen.ecommerce.modules.inventory.entity.InventoryTransactionType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** InventoryService appends a row here for every stock_quantity/reserved_quantity mutation. */
public interface InventoryTransactionRepository
        extends JpaRepository<InventoryTransaction, UUID>, JpaSpecificationExecutor<InventoryTransaction> {

    void deleteAllByVariantIdIn(List<UUID> variantIds);

    /**
     * Whether this order ever had its stock really deducted (ORDER_CONFIRM_DEDUCT,
     * fired once when the order first reached CONFIRMED). Used to pick the right
     * inventory side-effect when cancelling after confirmation: release() only
     * undoes a reservation, it must not be used once real stock was deducted -
     * restockReturn() is the correct undo there. See OrderService.applyStatusTransition.
     */
    boolean existsByOrder_IdAndType(UUID orderId, InventoryTransactionType type);

    boolean existsByIdempotencyKey(String idempotencyKey);
}
