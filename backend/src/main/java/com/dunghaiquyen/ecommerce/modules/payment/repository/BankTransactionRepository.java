package com.dunghaiquyen.ecommerce.modules.payment.repository;

import com.dunghaiquyen.ecommerce.modules.payment.entity.BankTransaction;
import com.dunghaiquyen.ecommerce.modules.payment.entity.BankTransactionSource;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID> {

    Optional<BankTransaction> findBySourceAndExternalTransactionId(
            BankTransactionSource source, String externalTransactionId);
}
