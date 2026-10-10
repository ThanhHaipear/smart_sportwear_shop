package com.dunghaiquyen.ecommerce.modules.payment.repository;

import com.dunghaiquyen.ecommerce.modules.payment.entity.Payment;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findAllByOrderIdOrderByCreatedAtDesc(UUID orderId);

    Optional<Payment> findFirstByOrderIdOrderByCreatedAtDesc(UUID orderId);

    Optional<Payment> findFirstByOrderIdAndStatusOrderByCreatedAtDesc(UUID orderId, PaymentStatus status);

    Optional<Payment> findFirstByOrderIdAndStatusInOrderByCreatedAtDesc(
            UUID orderId, Collection<PaymentStatus> statuses);

    /** Plain (unlocked) lookup - for read-only callers outside a write transaction. */
    Optional<Payment> findByTransactionRef(String transactionRef);

    /**
     * Row-locked read used by the callback handler: transactionRef is unique
     * (DB-enforced), so locking the one row by that key before inspecting its
     * status is what makes duplicate-callback handling safe under concurrent
     * delivery - two callbacks for the same transactionRef cannot both observe
     * PENDING and both apply the terminal-state side effect.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.transactionRef = :ref")
    Optional<Payment> findByTransactionRefForUpdate(@Param("ref") String ref);
}
