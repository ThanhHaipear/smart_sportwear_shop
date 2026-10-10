package com.dunghaiquyen.ecommerce.modules.payment.repository;

import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentEvent;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentProvider;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {
    boolean existsByProviderAndProviderEventKey(PaymentProvider provider, String providerEventKey);
}
