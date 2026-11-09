package com.dunghaiquyen.ecommerce.modules.notification.service;

import com.dunghaiquyen.ecommerce.common.exception.BusinessRuleException;
import com.dunghaiquyen.ecommerce.common.exception.ResourceNotFoundException;
import com.dunghaiquyen.ecommerce.common.mail.MailService;
import com.dunghaiquyen.ecommerce.common.response.PageMeta;
import com.dunghaiquyen.ecommerce.common.time.AppTimeZone;
import com.dunghaiquyen.ecommerce.modules.notification.dto.AdminNotificationListQuery;
import com.dunghaiquyen.ecommerce.modules.notification.dto.NotificationListQuery;
import com.dunghaiquyen.ecommerce.modules.notification.dto.NotificationResponse;
import com.dunghaiquyen.ecommerce.modules.notification.entity.Notification;
import com.dunghaiquyen.ecommerce.modules.notification.entity.NotificationChannel;
import com.dunghaiquyen.ecommerce.modules.notification.entity.NotificationStatus;
import com.dunghaiquyen.ecommerce.modules.notification.entity.NotificationType;
import com.dunghaiquyen.ecommerce.modules.notification.repository.NotificationRepository;
import com.dunghaiquyen.ecommerce.modules.notification.repository.spec.NotificationSpecifications;
import com.dunghaiquyen.ecommerce.modules.notification.template.EmailContent;
import com.dunghaiquyen.ecommerce.modules.notification.template.NotificationTemplates;
import com.dunghaiquyen.ecommerce.modules.order.entity.Order;
import com.dunghaiquyen.ecommerce.modules.user.entity.User;
import com.dunghaiquyen.ecommerce.modules.user.entity.UserRole;
import com.dunghaiquyen.ecommerce.modules.user.entity.UserStatus;
import com.dunghaiquyen.ecommerce.modules.user.api.UserReferenceQuery;
import java.time.Duration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Single entry point for every outbound notification (Phase O): builds the
 * subject/body via {@link NotificationTemplates} and always writes one
 * {@link Notification} row recording the outcome. Order lifecycle email is
 * queued only after the surrounding transaction commits and delivered by
 * {@link NotificationDeliveryService}; manual resend and password-reset delivery
 * remain synchronous because their API contracts need an immediate outcome.
 *
 * <p><b>Failure tradeoff:</b> SMTP failure is recorded as FAILED and never rolls
 * back the business action. The bounded executor is an in-process queue, not a
 * durable broker; rows left PENDING after an abrupt shutdown remain visible for
 * operational recovery/manual resend.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    /** Resend is manual/admin-triggered this phase (see NotificationService class javadoc on retry strategy) - these two rules are what keep it from being abused. */
    private static final int MAX_RESEND_ATTEMPTS = 5;
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(30);
    private static final Set<NotificationStatus> RESENDABLE_STATUSES = Set.of(NotificationStatus.FAILED, NotificationStatus.SENT);

    private final NotificationRepository notificationRepository;
    private final MailService mailService;
    private final NotificationTemplates notificationTemplates;
    private final NotificationBroadcaster broadcaster;
    private final NotificationDeliveryService deliveryService;
    private final UserReferenceQuery userReferenceQuery;

    public NotificationService(
            NotificationRepository notificationRepository,
            MailService mailService,
            NotificationTemplates notificationTemplates,
            NotificationBroadcaster broadcaster,
            NotificationDeliveryService deliveryService,
            UserReferenceQuery userReferenceQuery) {
        this.notificationRepository = notificationRepository;
        this.mailService = mailService;
        this.notificationTemplates = notificationTemplates;
        this.broadcaster = broadcaster;
        this.deliveryService = deliveryService;
        this.userReferenceQuery = userReferenceQuery;
    }

    public record ListResult(List<NotificationResponse> items, PageMeta meta) {
    }

    @Transactional
    public void notifyOrderCreated(Order order) {
        queueAfterCommit(order.getUser(), order, NotificationType.ORDER_CREATED, notificationTemplates.orderCreated(order));
        notifyActiveAdmins(order, NotificationType.ADMIN_ORDER_CREATED, notificationTemplates.adminOrderCreated(order));
    }

    @Transactional
    public void notifyAdminsOrderCancelled(Order order) {
        notifyActiveAdmins(order, NotificationType.ADMIN_ORDER_CANCELLED, notificationTemplates.adminOrderCancelled(order));
    }

    @Transactional
    public void notifyCancellationApproved(Order order) {
        queueAfterCommit(order.getUser(), order, NotificationType.CANCELLATION_APPROVED,
                notificationTemplates.cancellationApproved(order));
    }

    @Transactional
    public void notifyCancellationRejected(Order order, String reason) {
        queueAfterCommit(order.getUser(), order, NotificationType.CANCELLATION_REJECTED,
                notificationTemplates.cancellationRejected(order, reason));
    }

    private void notifyActiveAdmins(Order order, NotificationType type, EmailContent content) {
        userReferenceQuery.findActiveAdmins()
                .forEach(admin -> queueAfterCommit(admin, order, type, content));
    }

    @Transactional
    public void notifyOrderCancelled(Order order) {
        queueAfterCommit(order.getUser(), order, NotificationType.ORDER_CANCELLED, notificationTemplates.orderCancelled(order));
    }

    @Transactional
    public void notifyOrderDelivered(Order order) {
        queueAfterCommit(order.getUser(), order, NotificationType.ORDER_DELIVERED, notificationTemplates.orderDelivered(order));
    }

    /**
     * Triggered by ShipmentService when a SHIPMENT's own status transitions to
     * SHIPPING (not by Order's own orderStatus reaching its SHIPPING value) -
     * the shipment-status transition is the more precise "the order has
     * actually been handed to a carrier" signal. See ShipmentService's class
     * javadoc for why these two state machines stay independent.
     */
    @Transactional
    public void notifyOrderShipping(Order order) {
        queueAfterCommit(order.getUser(), order, NotificationType.ORDER_SHIPPING, notificationTemplates.orderShipping(order));
    }

    @Transactional
    public void notifyOrderStatusUpdated(Order order, String oldStatus, String newStatus) {
        queueAfterCommit(order.getUser(), order, NotificationType.ORDER_STATUS_UPDATED,
                notificationTemplates.orderStatusUpdated(order, oldStatus, newStatus));
    }

    @Transactional
    public void notifyRefundCompleted(Order order, String refundCode, BigDecimal refundAmount) {
        queueAfterCommit(order.getUser(), order, NotificationType.REFUND_COMPLETED,
                notificationTemplates.refundCompleted(order, refundCode, refundAmount));
    }

    @Transactional
    public void notifyPaymentCompleted(Order order, BigDecimal paymentAmount) {
        queueAfterCommit(order.getUser(), order, NotificationType.PAYMENT_COMPLETED,
                notificationTemplates.paymentCompleted(order, paymentAmount));
    }

    @Transactional
    public void notifyPasswordReset(User user, String resetLink, int ttlMinutes) {
        send(user, null, NotificationType.PASSWORD_RESET, notificationTemplates.passwordReset(resetLink, ttlMinutes));
    }

    @Transactional(readOnly = true)
    public ListResult list(AdminNotificationListQuery query) {
        Specification<Notification> spec = Specification.unrestricted();
        if (query.type() != null) {
            spec = spec.and(NotificationSpecifications.hasType(query.type()));
        }
        if (query.status() != null) {
            spec = spec.and(NotificationSpecifications.hasStatus(query.status()));
        }
        if (query.channel() != null) {
            spec = spec.and(NotificationSpecifications.hasChannel(query.channel()));
        }
        if (query.userId() != null) {
            spec = spec.and(NotificationSpecifications.belongsToUser(query.userId()));
        }
        if (query.orderId() != null) {
            spec = spec.and(NotificationSpecifications.belongsToOrder(query.orderId()));
        }
        if (query.dateFrom() != null) {
            spec = spec.and(NotificationSpecifications.createdFrom(
                    query.dateFrom().atStartOfDay(AppTimeZone.ZONE).toInstant()));
        }
        if (query.dateTo() != null) {
            spec = spec.and(NotificationSpecifications.createdTo(
                    query.dateTo().atTime(LocalTime.MAX).atZone(AppTimeZone.ZONE).toInstant()));
        }
        Pageable pageable = PageRequest.of(
                resolvePageIndex(query.page()), resolveLimit(query.limit()), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Notification> page = notificationRepository.findAll(spec, pageable);
        List<NotificationResponse> items = page.getContent().stream().map(this::toResponse).toList();
        return new ListResult(items, PageMeta.from(page));
    }

    /**
     * Self-service history (GET /api/v1/notifications/me) - userId always
     * comes from the authenticated caller, never a request param, so a user
     * can never see another user's notifications by guessing/forging a query param.
     */
    @Transactional(readOnly = true)
    public ListResult listMine(UUID userId, NotificationListQuery query) {
        Specification<Notification> spec = NotificationSpecifications.belongsToUser(userId);
        if (query.type() != null) {
            spec = spec.and(NotificationSpecifications.hasType(query.type()));
        }
        if (query.status() != null) {
            spec = spec.and(NotificationSpecifications.hasStatus(query.status()));
        }
        Pageable pageable = PageRequest.of(
                resolvePageIndex(query.page()), resolveLimit(query.limit()), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Notification> page = notificationRepository.findAll(spec, pageable);
        List<NotificationResponse> items = page.getContent().stream().map(this::toResponse).toList();
        return new ListResult(items, PageMeta.from(page));
    }

    /** In-app unread badge count for the authenticated user. */
    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    /**
     * Mark a single notification read. userId comes from the authenticated
     * caller and is checked against the row's owner, so a user can never mark
     * (or by extension probe the existence of) another user's notification -
     * a foreign id is reported as not-found, identical to a missing one.
     * Idempotent: re-reading an already-read row is a no-op, not an error.
     */
    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .filter(n -> n.getUser() != null && n.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
            notificationRepository.save(notification);
        }
    }

    /** Mark all of the user's unread notifications read in one statement; returns the count updated. */
    @Transactional
    public int markAllRead(UUID userId) {
        return notificationRepository.markAllReadForUser(userId, Instant.now());
    }

    /**
     * Admin-triggered, single attempt per call (Notification Operations
     * phase) - no scheduled/automatic retry exists. Deliberate scope
     * decision, not an oversight: a synchronous send already happens inside
     * every business transaction (order creation, status transition,
     * forgot-password) and a FAILED row today is almost always either a
     * transient SMTP blip (worth retrying) or a structurally bad recipient
     * (retrying forever wastes cycles and risks the sending domain's
     * reputation) - nothing in a FAILED row alone tells those two cases
     * apart, but a human glancing at errorMessage before clicking resend
     * can. At current (order-driven transactional-only) email volume, that
     * manual step is cheap enough that a `@Scheduled` poller - with its own
     * new failure modes (overlapping runs, multi-instance double-firing) -
     * would be solving a problem this phase does not yet have. The cap/
     * cooldown rules below are written generically enough that a future
     * scheduled job could call this exact method on eligible FAILED rows
     * without any rework, if/when volume justifies it.
     *
     * <p><b>Eligibility:</b> only an ORIGINAL notification (resendOf == null)
     * in SENT or FAILED status may be resent - never PENDING (an
     * inconsistent/in-flight state, never actually persisted by {@link #send}
     * today but guarded defensively), and never a resend record itself
     * (keeps the audit chain flat: one original, N flat attempts against it,
     * rather than a tree that would need to be walked to find the "real"
     * root for the cap/cooldown check). Resending a SENT notification is
     * intentionally allowed (not just FAILED) - "customer says they never
     * got it" is a legitimate support case - gated by the same cap/cooldown
     * as a failure-recovery resend.
     *
     * <p><b>Audit strategy (chosen over mutating the original row):</b> every
     * resend INSERTs a new Notification row with resendOf pointing at the
     * original, and the original's own status/subject/body/sentAt/
     * errorMessage are never touched - only its resendCount/lastResendAt
     * bookkeeping changes. This means the original FAILED row stays FAILED
     * forever in history even after a later resend attempt SUCCEEDS - which
     * is correct: it really did fail, at that time, for that reason; the
     * resend's own row is where the recovery is recorded. The tradeoff this
     * accepts is more rows per "conceptual" notification (a FAILED-then-
     * recovered notification is 2+ rows, not 1 row with mutated history) -
     * judged worth it because collapsing them would erase exactly the
     * failure-then-recovery timeline an admin reviewing send health needs.
     *
     * <p><b>Re-render, never:</b> the new row copies recipient/subject/body
     * VERBATIM off the original - it never re-renders from the source Order/
     * User. This is what makes "the source order/user no longer has enough
     * data to re-render" structurally impossible here: recipient/subject/
     * body are already a fully-resolved snapshot on every row (recipient in
     * particular is its own column, not derived from the now-nullable user
     * FK), exactly so a since-deleted user/order can never block a resend.
     */
    @Transactional
    public NotificationResponse resend(UUID notificationId) {
        Notification original = notificationRepository.findByIdForUpdate(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));

        if (original.getResendOf() != null) {
            throw new BusinessRuleException(
                    HttpStatus.CONFLICT, "Cannot resend a resend record - resend the original notification instead");
        }
        if (!RESENDABLE_STATUSES.contains(original.getStatus())) {
            throw new BusinessRuleException(
                    HttpStatus.CONFLICT, "Cannot resend a " + original.getStatus() + " notification");
        }
        if (original.getResendCount() >= MAX_RESEND_ATTEMPTS) {
            throw new BusinessRuleException(
                    HttpStatus.CONFLICT, "Resend limit reached for this notification (max " + MAX_RESEND_ATTEMPTS + ")");
        }
        if (original.getLastResendAt() != null
                && Duration.between(original.getLastResendAt(), Instant.now()).compareTo(RESEND_COOLDOWN) < 0) {
            throw new BusinessRuleException(HttpStatus.CONFLICT, "Please wait before resending this notification again");
        }

        Notification copy = new Notification();
        copy.setUser(original.getUser());
        copy.setOrder(original.getOrder());
        copy.setType(original.getType());
        copy.setChannel(original.getChannel());
        copy.setRecipient(original.getRecipient());
        copy.setSubject(original.getSubject());
        copy.setBody(original.getBody());
        copy.setStatus(NotificationStatus.PENDING);
        copy.setResendOf(original);
        deliverAndSave(copy);

        original.setResendCount(original.getResendCount() + 1);
        original.setLastResendAt(Instant.now());
        notificationRepository.save(original);

        return toResponse(copy);
    }

    private void send(User user, Order order, NotificationType type, EmailContent content) {
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setOrder(order);
        notification.setType(type);
        notification.setChannel(NotificationChannel.EMAIL);
        notification.setRecipient(user.getEmail());
        notification.setSubject(content.subject());
        notification.setBody(content.body());
        notification.setStatus(NotificationStatus.PENDING);
        deliverAndSave(notification);
    }

    private void queueAfterCommit(User user, Order order, NotificationType type, EmailContent content) {
        Notification notification = new Notification();
        notification.setUser(user);
        notification.setOrder(order);
        notification.setType(type);
        notification.setChannel(NotificationChannel.EMAIL);
        notification.setRecipient(user.getEmail());
        notification.setSubject(content.subject());
        notification.setBody(content.body());
        notification.setStatus(NotificationStatus.PENDING);
        notification = notificationRepository.save(notification);

        UUID notificationId = notification.getId();
        UUID userId = user.getId();
        NotificationResponse realtimePayload = toResponse(notification);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    // Realtime UI synchronization must not wait for SMTP. Email
                    // delivery remains asynchronous and may take several seconds.
                    broadcaster.broadcast(userId, realtimePayload);
                    scheduleDelivery(notificationId);
                }
            });
        } else {
            broadcaster.broadcast(userId, realtimePayload);
            scheduleDelivery(notificationId);
        }
    }

    private void scheduleDelivery(UUID notificationId) {
        try {
            deliveryService.deliverAsync(notificationId);
        } catch (RuntimeException ex) {
            // The business transaction is already committed. Mark the row
            // FAILED in a new transaction so admin resend can recover it.
            log.error("Notification task rejected id={}: {}", notificationId, ex.getMessage());
            try {
                deliveryService.markSchedulingFailed(notificationId, "Notification queue unavailable");
            } catch (RuntimeException markFailure) {
                log.error("Could not mark rejected notification {} as FAILED: {}", notificationId, markFailure.getMessage());
            }
        }
    }

    /** The one place that actually calls MailService.send and records the outcome - used by both the organic send() path and resend(). */
    private void deliverAndSave(Notification notification) {
        try {
            mailService.send(notification.getRecipient(), notification.getSubject(), notification.getBody());
            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(Instant.now());
        } catch (RuntimeException ex) {
            log.warn(
                    "Notification send failed type={} recipient={}: {}",
                    notification.getType(), notification.getRecipient(), ex.getMessage());
            notification.setStatus(NotificationStatus.FAILED);
            notification.setErrorMessage(ex.getMessage());
        }
        notificationRepository.save(notification);
        pushToStreamAfterCommit(notification);
    }

    /**
     * Fire the real-time SSE push only AFTER the surrounding transaction commits,
     * mirroring the same "never surface a notification the business action rolled
     * back" guarantee the email path already relies on. The payload is captured
     * eagerly (while the entity is still attached) so the after-commit callback
     * touches no lazy associations. A no-op when the user has no open stream.
     */
    private void pushToStreamAfterCommit(Notification notification) {
        if (notification.getUser() == null) {
            return;
        }
        UUID userId = notification.getUser().getId();
        NotificationResponse payload = toResponse(notification);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    broadcaster.broadcast(userId, payload);
                }
            });
        } else {
            broadcaster.broadcast(userId, payload);
        }
    }

    private int resolvePageIndex(Integer page) {
        return (page != null && page > 0) ? page - 1 : 0;
    }

    private int resolveLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getUser() != null ? notification.getUser().getId() : null,
                notification.getOrder() != null ? notification.getOrder().getId() : null,
                notification.getType(),
                notification.getChannel(),
                notification.getRecipient(),
                notification.getSubject(),
                notification.getBody(),
                notification.getStatus(),
                notification.getErrorMessage(),
                notification.getCreatedAt(),
                notification.getSentAt(),
                notification.getReadAt(),
                notification.getResendOf() != null ? notification.getResendOf().getId() : null,
                notification.getResendCount(),
                notification.getLastResendAt());
    }
}
