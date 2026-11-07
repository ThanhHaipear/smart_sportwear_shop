package com.dunghaiquyen.ecommerce.modules.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dunghaiquyen.ecommerce.config.AppOrderProperties;
import com.dunghaiquyen.ecommerce.config.AppVietQrProperties;
import com.dunghaiquyen.ecommerce.modules.inventory.service.InventoryService;
import com.dunghaiquyen.ecommerce.modules.order.entity.Order;
import com.dunghaiquyen.ecommerce.modules.order.entity.OrderStatus;
import com.dunghaiquyen.ecommerce.modules.order.entity.PaymentMethod;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderItemRepository;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderRepository;
import com.dunghaiquyen.ecommerce.modules.payment.entity.Payment;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentProvider;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentStatus;
import com.dunghaiquyen.ecommerce.modules.payment.repository.PaymentRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PendingPaymentExpiryServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderItemRepository orderItemRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private InventoryService inventoryService;

    private PendingPaymentExpiryService service;

    @BeforeEach
    void setUp() {
        service = new PendingPaymentExpiryService(
                orderRepository,
                orderItemRepository,
                paymentRepository,
                inventoryService,
                new AppOrderProperties(30),
                new AppVietQrProperties(false, null, null, null, 30));
    }

    @Test
    void expiresVietQrOrderAndItsPendingPayment() {
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setOrderStatus(OrderStatus.PENDING_CONFIRMATION);
        order.setPaymentMethod(PaymentMethod.VIETQR);
        order.setPaymentStatus(PaymentStatus.PENDING);

        Payment payment = new Payment();
        payment.setId(UUID.randomUUID());
        payment.setOrder(order);
        payment.setProvider(PaymentProvider.BANK_TRANSFER);
        payment.setStatus(PaymentStatus.PENDING);

        when(orderRepository
                .findTop100ByOrderStatusAndPaymentMethodAndPaymentStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(
                        eq(OrderStatus.PENDING_CONFIRMATION),
                        eq(PaymentMethod.VNPAY),
                        any(),
                        any(Instant.class)))
                .thenReturn(List.of());
        when(orderRepository
                .findTop100ByOrderStatusAndPaymentMethodAndPaymentStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(
                        eq(OrderStatus.PENDING_CONFIRMATION),
                        eq(PaymentMethod.VIETQR),
                        any(),
                        any(Instant.class)))
                .thenReturn(List.of(order));
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(orderItemRepository.findAllByOrderIdOrderByIdAsc(order.getId())).thenReturn(List.of());
        when(paymentRepository.findFirstByOrderIdAndProviderAndStatusOrderByCreatedAtDesc(
                order.getId(), PaymentProvider.BANK_TRANSFER, PaymentStatus.PENDING))
                .thenReturn(Optional.of(payment));

        service.expirePendingOnlinePaymentOrders();

        assertThat(order.getOrderStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(order.getInternalNote()).isEqualTo("Automatically cancelled because online payment expired");
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        verify(paymentRepository).save(payment);
        verify(orderRepository).save(order);
    }
}
