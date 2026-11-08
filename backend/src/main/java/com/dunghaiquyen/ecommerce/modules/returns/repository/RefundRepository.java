package com.dunghaiquyen.ecommerce.modules.returns.repository;

import com.dunghaiquyen.ecommerce.modules.returns.entity.Refund;
import com.dunghaiquyen.ecommerce.modules.returns.entity.RefundStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, UUID>, JpaSpecificationExecutor<Refund> {

    List<Refund> findAllByReturnRequestIdOrderByCreatedAtDesc(UUID returnId);

    List<Refund> findAllByOrderIdOrderByCreatedAtDesc(UUID orderId);

    boolean existsByOrderIdAndStatusIn(UUID orderId, Collection<RefundStatus> statuses);

    Optional<Refund> findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
            UUID orderId, Collection<RefundStatus> statuses);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.id = :id")
    Optional<Refund> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Refund r where r.refundCode = :refundCode")
    Optional<Refund> findByRefundCodeForUpdate(@Param("refundCode") String refundCode);

    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.order.id = :orderId and r.status = com.dunghaiquyen.ecommerce.modules.returns.entity.RefundStatus.COMPLETED")
    BigDecimal sumCompletedAmountByOrderId(@Param("orderId") UUID orderId);

    @Query("select coalesce(sum(r.amount), 0) from Refund r where r.order.id = :orderId and r.status in :statuses")
    BigDecimal sumAmountByOrderIdAndStatusIn(
            @Param("orderId") UUID orderId, @Param("statuses") Collection<RefundStatus> statuses);
}
