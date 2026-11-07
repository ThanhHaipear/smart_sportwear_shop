package com.dunghaiquyen.ecommerce.modules.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.config.AppVietQrProperties;
import com.dunghaiquyen.ecommerce.modules.order.entity.Order;
import com.dunghaiquyen.ecommerce.modules.order.entity.OrderStatus;
import com.dunghaiquyen.ecommerce.modules.order.entity.PaymentMethod;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderRepository;
import com.dunghaiquyen.ecommerce.modules.payment.dto.CreateVietQrPaymentRequest;
import com.dunghaiquyen.ecommerce.modules.payment.entity.Payment;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentProvider;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentStatus;
import com.dunghaiquyen.ecommerce.modules.payment.repository.PaymentRepository;
import com.dunghaiquyen.ecommerce.modules.user.entity.User;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VietQrPaymentServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentRepository paymentRepository;

    private VietQrPaymentService service;

    @BeforeEach
    void setUp() {
        service = new VietQrPaymentService(
                orderRepository,
                paymentRepository,
                new AppVietQrProperties(true, "970422", "1326045980", "LE THANH H", 30));
    }

    @Test
    void createsPendingBankTransferFromTrustedOrderData() {
        UUID userId = UUID.randomUUID();
        Order order = order(userId, PaymentMethod.VIETQR);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findFirstByOrderIdAndProviderOrderByCreatedAtDesc(
                order.getId(), PaymentProvider.BANK_TRANSFER)).thenReturn(Optional.empty());
        when(paymentRepository.existsByProviderAndTransactionRef(
                any(PaymentProvider.class), any(String.class))).thenReturn(false);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            payment.setId(UUID.randomUUID());
            return payment;
        });

        var response = service.createOrResume(
                userId, new CreateVietQrPaymentRequest(order.getId()));

        assertThat(response.orderId()).isEqualTo(order.getId());
        assertThat(response.amount()).isEqualByComparingTo("250000.00");
        assertThat(response.currency()).isEqualTo("VND");
        assertThat(response.bankBin()).isEqualTo("970422");
        assertThat(response.accountNumber()).isEqualTo("1326045980");
        assertThat(response.transactionRef()).matches("DH[A-HJ-NP-Z2-9]{10}");
        assertThat(response.expiresAt()).isEqualTo(order.getCreatedAt().plus(30, ChronoUnit.MINUTES));
        assertThat(order.getPaymentStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void resumesExistingUnexpiredAttemptWithoutCreatingAnotherPayment() {
        UUID userId = UUID.randomUUID();
        Order order = order(userId, PaymentMethod.VIETQR);
        Payment existing = new Payment();
        existing.setId(UUID.randomUUID());
        existing.setOrder(order);
        existing.setProvider(PaymentProvider.BANK_TRANSFER);
        existing.setTransactionRef("DHABCDEFG234");
        existing.setAmount(order.getTotalAmount());
        existing.setStatus(PaymentStatus.PENDING);
        existing.setExpiresAt(Instant.now().plusSeconds(600));

        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findFirstByOrderIdAndProviderOrderByCreatedAtDesc(
                order.getId(), PaymentProvider.BANK_TRANSFER)).thenReturn(Optional.of(existing));

        var response = service.createOrResume(
                userId, new CreateVietQrPaymentRequest(order.getId()));

        assertThat(response.paymentId()).isEqualTo(existing.getId());
        assertThat(response.transactionRef()).isEqualTo(existing.getTransactionRef());
        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void cancelsExpiredAttemptBeforeCreatingItsReplacement() {
        UUID userId = UUID.randomUUID();
        Order order = order(userId, PaymentMethod.VIETQR);
        Payment expired = new Payment();
        expired.setId(UUID.randomUUID());
        expired.setOrder(order);
        expired.setProvider(PaymentProvider.BANK_TRANSFER);
        expired.setTransactionRef("DHABCDEFG234");
        expired.setAmount(order.getTotalAmount());
        expired.setStatus(PaymentStatus.PENDING);
        expired.setExpiresAt(Instant.now().minusSeconds(1));

        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));
        when(paymentRepository.findFirstByOrderIdAndProviderOrderByCreatedAtDesc(
                order.getId(), PaymentProvider.BANK_TRANSFER)).thenReturn(Optional.of(expired));
        when(paymentRepository.existsByProviderAndTransactionRef(
                any(PaymentProvider.class), any(String.class))).thenReturn(false);
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            if (payment.getId() == null) {
                payment.setId(UUID.randomUUID());
            }
            return payment;
        });

        var response = service.createOrResume(
                userId, new CreateVietQrPaymentRequest(order.getId()));

        assertThat(expired.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        assertThat(response.paymentId()).isNotEqualTo(expired.getId());
        verify(paymentRepository).flush();
        verify(paymentRepository, times(2)).save(any(Payment.class));
    }

    @Test
    void hidesOrdersOwnedByAnotherCustomer() {
        Order order = order(UUID.randomUUID(), PaymentMethod.VIETQR);
        when(orderRepository.findByIdForUpdate(order.getId())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.createOrResume(
                        UUID.randomUUID(), new CreateVietQrPaymentRequest(order.getId())))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Order not found");
    }

    @Test
    void rejectsRequestsWhileFeatureIsDisabled() {
        VietQrPaymentService disabled = new VietQrPaymentService(
                orderRepository,
                paymentRepository,
                new AppVietQrProperties(false, null, null, null, 30));

        assertThatThrownBy(() -> disabled.createOrResume(
                        UUID.randomUUID(), new CreateVietQrPaymentRequest(UUID.randomUUID())))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("VietQR payment is disabled");
        verify(orderRepository, never()).findByIdForUpdate(any(UUID.class));
    }

    private Order order(UUID userId, PaymentMethod paymentMethod) {
        User user = new User();
        user.setId(userId);
        Order order = new Order();
        order.setId(UUID.randomUUID());
        order.setUser(user);
        order.setPaymentMethod(paymentMethod);
        order.setPaymentStatus(PaymentStatus.UNPAID);
        order.setOrderStatus(OrderStatus.PENDING_CONFIRMATION);
        order.setTotalAmount(new BigDecimal("250000.00"));
        order.setCurrencyCode("VND");
        order.setCreatedAt(Instant.now());
        return order;
    }
}
