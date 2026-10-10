package com.dunghaiquyen.ecommerce.modules.order.repository;

import com.dunghaiquyen.ecommerce.modules.order.entity.OrderStatusHistory;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, UUID> {
    List<OrderStatusHistory> findAllByOrderIdOrderByOccurredAtAsc(UUID orderId);
}
