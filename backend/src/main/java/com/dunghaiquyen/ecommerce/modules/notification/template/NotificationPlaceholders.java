package com.dunghaiquyen.ecommerce.modules.notification.template;

import com.dunghaiquyen.ecommerce.modules.notification.entity.NotificationType;
import java.util.Map;
import java.util.EnumMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single source of truth for "which {placeholder} tokens does each
 * NotificationType actually have data for at send time" - used both by
 * NotificationTemplates (to build the substitution map) and by
 * NotificationTemplateService (to reject an admin-submitted template that
 * references a token NotificationService can never fill in, which would
 * otherwise reach a customer as a literal, un-substituted "{whatever}").
 */
public final class NotificationPlaceholders {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("\\{([a-zA-Z]+)\\}");

    private static final Map<NotificationType, Set<String>> ALLOWED = createAllowed();

    private static Map<NotificationType, Set<String>> createAllowed() {
        Map<NotificationType, Set<String>> allowed = new EnumMap<>(NotificationType.class);
        allowed.putAll(Map.of(
            NotificationType.ORDER_CREATED, Set.of("customerName", "orderCode", "totalAmount", "paymentMethod"),
            NotificationType.ORDER_CANCELLED, Set.of("customerName", "orderCode", "totalAmount"),
            NotificationType.ORDER_DELIVERED, Set.of("customerName", "orderCode", "totalAmount"),
            NotificationType.ORDER_SHIPPING, Set.of("customerName", "orderCode", "totalAmount"),
            NotificationType.ORDER_STATUS_UPDATED, Set.of("customerName", "orderCode", "oldStatus", "newStatus"),
            NotificationType.ADMIN_ORDER_CREATED, Set.of("customerName", "orderCode", "totalAmount", "paymentMethod"),
            NotificationType.ADMIN_ORDER_CANCELLED, Set.of("customerName", "orderCode", "totalAmount"),
            NotificationType.CANCELLATION_APPROVED, Set.of("customerName", "orderCode", "totalAmount"),
            NotificationType.CANCELLATION_REJECTED, Set.of("customerName", "orderCode", "rejectionReason"),
            NotificationType.PASSWORD_RESET, Set.of("resetLink", "ttlMinutes")));
        allowed.put(NotificationType.REFUND_COMPLETED,
                Set.of("customerName", "orderCode", "refundCode", "refundAmount"));
        allowed.put(NotificationType.PAYMENT_COMPLETED,
                Set.of("customerName", "orderCode", "paymentAmount"));
        return Map.copyOf(allowed);
    }

    private NotificationPlaceholders() {
    }

    public static Set<String> allowedFor(NotificationType type) {
        return ALLOWED.getOrDefault(type, Set.of());
    }

    /** Every {token} actually referenced in the given text, regardless of whether it is allowed. */
    public static Set<String> tokensIn(String text) {
        if (text == null) {
            return Set.of();
        }
        Set<String> found = new java.util.LinkedHashSet<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
