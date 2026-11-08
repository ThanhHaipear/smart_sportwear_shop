package com.dunghaiquyen.ecommerce.modules.payment.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.config.AppVietQrProperties;
import com.dunghaiquyen.ecommerce.modules.order.entity.Order;
import com.dunghaiquyen.ecommerce.modules.order.entity.OrderStatus;
import com.dunghaiquyen.ecommerce.modules.order.entity.PaymentMethod;
import com.dunghaiquyen.ecommerce.modules.order.repository.OrderRepository;
import com.dunghaiquyen.ecommerce.modules.payment.dto.CreateVietQrPaymentRequest;
import com.dunghaiquyen.ecommerce.modules.payment.dto.CreateVietQrPaymentResponse;
import com.dunghaiquyen.ecommerce.modules.payment.entity.Payment;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentProvider;
import com.dunghaiquyen.ecommerce.modules.payment.entity.PaymentStatus;
import com.dunghaiquyen.ecommerce.modules.payment.repository.PaymentRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class VietQrPaymentService {

    private static final char[] REFERENCE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int REFERENCE_RANDOM_LENGTH = 10;
    private static final int MAX_REFERENCE_GENERATION_ATTEMPTS = 8;

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final AppVietQrProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public VietQrPaymentService(
            OrderRepository orderRepository,
            PaymentRepository paymentRepository,
            AppVietQrProperties properties) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.properties = properties;
    }

    /**
     * Creates the one active bank-transfer attempt for an order, or resumes it.
     * Locking the order serializes concurrent double-clicks before the active
     * payment lookup and insert.
     */
    @Transactional
    public CreateVietQrPaymentResponse createOrResume(
            UUID userId, CreateVietQrPaymentRequest request) {
        if (!properties.isEnabled()) {
            throw new BusinessRuleException(HttpStatus.SERVICE_UNAVAILABLE, "VietQR payment is disabled");
        }

        Order order = orderRepository.findByIdForUpdate(request.orderId())
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (!order.getUser().getId().equals(userId)) {
            throw new ResourceNotFoundException("Order not found");
        }
        if (order.getPaymentMethod() != PaymentMethod.VIETQR) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Order is not eligible for VietQR payment");
        }
        if (order.getOrderStatus() == OrderStatus.CANCELLED) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Order is cancelled");
        }
        if (order.getPaymentStatus() == PaymentStatus.PAID) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY, "Order is already paid");
        }

        Instant now = Instant.now();
        Instant hardDeadline = order.getCreatedAt()
                .plus(properties.effectivePaymentExpiryMinutes(), ChronoUnit.MINUTES);
        if (!hardDeadline.isAfter(now)) {
            throw new BusinessRuleException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Order payment window has expired");
        }

        Optional<Payment> latest = paymentRepository
                .findFirstByOrderIdAndProviderOrderByCreatedAtDesc(order.getId(), PaymentProvider.BANK_TRANSFER);
        if (latest.isPresent() && latest.get().getStatus() == PaymentStatus.PENDING) {
            Payment existing = latest.get();
            if (existing.getExpiresAt() != null && existing.getExpiresAt().isAfter(now)) {
                return toResponse(existing);
            }
            existing.setStatus(PaymentStatus.CANCELLED);
            paymentRepository.save(existing);
            // Hibernate may otherwise execute the new INSERT before this UPDATE,
            // tripping the partial unique index for one active provider/order.
            paymentRepository.flush();
        }

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setProvider(PaymentProvider.BANK_TRANSFER);
        payment.setTransactionRef(generateUniqueReference());
        payment.setAmount(order.getTotalAmount());
        payment.setStatus(PaymentStatus.PENDING);
        payment.setExpiresAt(hardDeadline);
        payment.setBankCode(properties.bankBin());
        payment = paymentRepository.save(payment);

        if (order.getPaymentStatus() != PaymentStatus.PENDING) {
            order.setPaymentStatus(PaymentStatus.PENDING);
            orderRepository.save(order);
        }
        return toResponse(payment);
    }

    @Transactional(readOnly = true)
    public CreateVietQrPaymentResponse getPayment(UUID userId, UUID paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found"));
        Order order = payment.getOrder();
        if (!order.getUser().getId().equals(userId)
                || payment.getProvider() != PaymentProvider.BANK_TRANSFER
                || order.getPaymentMethod() != PaymentMethod.VIETQR) {
            throw new ResourceNotFoundException("Payment not found");
        }
        return toResponse(payment);
    }

    private String generateUniqueReference() {
        for (int attempt = 0; attempt < MAX_REFERENCE_GENERATION_ATTEMPTS; attempt++) {
            StringBuilder reference = new StringBuilder("DH");
            for (int i = 0; i < REFERENCE_RANDOM_LENGTH; i++) {
                reference.append(REFERENCE_ALPHABET[secureRandom.nextInt(REFERENCE_ALPHABET.length)]);
            }
            String candidate = reference.toString();
            if (!paymentRepository.existsByProviderAndTransactionRef(PaymentProvider.BANK_TRANSFER, candidate)) {
                return candidate;
            }
        }
        throw new BusinessRuleException(HttpStatus.CONFLICT,
                "Could not allocate a unique VietQR payment reference");
    }

    private CreateVietQrPaymentResponse toResponse(Payment payment) {
        Order order = payment.getOrder();
        return new CreateVietQrPaymentResponse(
                payment.getId(),
                order.getId(),
                payment.getTransactionRef(),
                payment.getAmount(),
                order.getCurrencyCode(),
                properties.bankBin(),
                properties.accountNumber(),
                properties.accountName(),
                payment.getStatus(),
                payment.getExpiresAt());
    }
}
