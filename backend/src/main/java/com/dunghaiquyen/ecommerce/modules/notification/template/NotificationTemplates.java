package com.dunghaiquyen.ecommerce.modules.notification.template;

import com.dunghaiquyen.ecommerce.modules.notification.entity.NotificationType;
import com.dunghaiquyen.ecommerce.modules.notification.repository.NotificationTemplateRepository;
import com.dunghaiquyen.ecommerce.modules.order.entity.Order;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Single, centralized place where every NotificationType's subject/body is
 * built (Notification Operations phase) - NotificationService never builds
 * mail text itself, it only ever calls one of these methods. Two-layer
 * lookup per send: if admin has customized this type via
 * PATCH /api/v1/admin/notification-templates/{id} (a row exists in
 * notification_templates), that row's subject/body is used; otherwise the
 * {@link #DEFAULTS} below - the exact same copy this class used to return
 * unconditionally before this phase - is used. Either way, {placeholder}
 * tokens are substituted with values read off the live Order/params at send
 * time; see NotificationPlaceholders for which tokens each type supports.
 *
 * <p>Was a static utility class before this phase; now a bean (needs the
 * repository) - NotificationService now calls instance methods on an
 * injected NotificationTemplates instead of static ones. No other call site
 * exists outside NotificationService.
 */
@Component
public class NotificationTemplates {

    /**
     * Byte-for-byte the same copy this class returned before this phase,
     * just with %s/%d format specifiers turned into named {tokens} - default
     * (un-customized) output is therefore unchanged, which is exactly what
     * NotificationIntegrationTest's exact-string assertions depend on.
     */
    private static final Map<NotificationType, EmailContent> DEFAULTS = createDefaults();

    private static Map<NotificationType, EmailContent> createDefaults() {
        Map<NotificationType, EmailContent> defaults = new EnumMap<>(NotificationType.class);
        defaults.putAll(Map.of(
            NotificationType.ORDER_CREATED, new EmailContent(
                    "Xác nhận đơn hàng {orderCode}",
                    """
                    Xin chào {customerName},

                    Cảm ơn bạn đã đặt hàng tại Smart Sportswear Shop. Đơn hàng {orderCode} của bạn đã được ghi nhận.

                    Tổng tiền: {totalAmount}
                    Phương thức thanh toán: {paymentMethod}

                    Chúng tôi sẽ thông báo cho bạn khi đơn hàng được xác nhận và giao đi.

                    Trân trọng."""),
            NotificationType.ORDER_CANCELLED, new EmailContent(
                    "Đơn hàng {orderCode} đã bị hủy",
                    """
                    Xin chào {customerName},

                    Đơn hàng {orderCode} của bạn đã bị hủy.

                    Tổng tiền đơn hàng: {totalAmount}

                    Nếu bạn đã thanh toán, chúng tôi sẽ liên hệ để hoàn tiền theo chính sách của cửa hàng.
                    Nếu đây không phải là yêu cầu của bạn, vui lòng liên hệ bộ phận hỗ trợ.

                    Trân trọng."""),
            NotificationType.ORDER_DELIVERED, new EmailContent(
                    "Đơn hàng {orderCode} đã được giao thành công",
                    """
                    Xin chào {customerName},

                    Đơn hàng {orderCode} của bạn đã được giao thành công.

                    Tổng tiền: {totalAmount}

                    Cảm ơn bạn đã mua sắm tại Smart Sportswear Shop. Nếu sản phẩm có vấn đề, vui lòng liên hệ bộ phận hỗ trợ trong vòng 7 ngày.

                    Trân trọng."""),
            NotificationType.ORDER_SHIPPING, new EmailContent(
                    "Đơn hàng {orderCode} đang được giao",
                    """
                    Xin chào {customerName},

                    Đơn hàng {orderCode} của bạn đang trên đường giao đến bạn.

                    Tổng tiền: {totalAmount}

                    Vui lòng giữ điện thoại liên lạc để đơn vị vận chuyển có thể liên hệ khi cần.

                    Trân trọng."""),
            NotificationType.ORDER_STATUS_UPDATED, new EmailContent(
                    "Cập nhật đơn hàng {orderCode}: {newStatus}",
                    "Xin chào {customerName}, trạng thái đơn hàng {orderCode} đã được cập nhật từ {oldStatus} sang {newStatus}."),
            NotificationType.ADMIN_ORDER_CREATED, new EmailContent(
                    "Có đơn hàng mới {orderCode}",
                    "Khách hàng {customerName} vừa tạo đơn {orderCode}, tổng tiền {totalAmount}, thanh toán {paymentMethod}."),
            NotificationType.ADMIN_ORDER_CANCELLED, new EmailContent(
                    "Đơn hàng {orderCode} có yêu cầu hủy",
                    "Khách hàng {customerName} vừa hủy hoặc yêu cầu hủy đơn {orderCode}, tổng tiền {totalAmount}."),
            NotificationType.CANCELLATION_APPROVED, new EmailContent(
                    "Yêu cầu hủy đơn {orderCode} đã được xác nhận",
                    "Xin chào {customerName}, cửa hàng đã xác nhận yêu cầu hủy đơn {orderCode}. Tổng tiền đơn hàng: {totalAmount}."),
            NotificationType.CANCELLATION_REJECTED, new EmailContent(
                    "Yêu cầu hủy đơn {orderCode} đã bị từ chối",
                    "Xin chào {customerName}, cửa hàng chưa thể chấp nhận yêu cầu hủy đơn {orderCode}. Lý do: {rejectionReason}."),
            NotificationType.PASSWORD_RESET, new EmailContent(
                    "Yêu cầu đặt lại mật khẩu",
                    """
                    Xin chào,

                    Chúng tôi nhận được yêu cầu đặt lại mật khẩu cho tài khoản của bạn.

                    Vui lòng nhấn vào đường dẫn sau để đặt lại mật khẩu (liên kết có hiệu lực trong {ttlMinutes} phút):
                    {resetLink}

                    Nếu bạn không yêu cầu đặt lại mật khẩu, vui lòng bỏ qua email này.

                    Trân trọng.""")));
        defaults.put(NotificationType.REFUND_COMPLETED, new EmailContent(
                "Hoàn tiền đơn hàng {orderCode} thành công",
                "Xin chào {customerName}, cửa hàng đã hoàn {refundAmount} cho đơn hàng {orderCode}. "
                        + "Mã hoàn tiền: {refundCode}."));
        defaults.put(NotificationType.PAYMENT_COMPLETED, new EmailContent(
                "Thanh toán đơn hàng {orderCode} thành công",
                "Xin chào {customerName}, thanh toán {paymentAmount} cho đơn hàng {orderCode} đã được xác nhận."));
        return Map.copyOf(defaults);
    }

    private final NotificationTemplateRepository templateRepository;

    public NotificationTemplates(NotificationTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    /** Used by NotificationTemplateSeeder to seed the initial, admin-editable row for every type. */
    public static EmailContent defaultFor(NotificationType type) {
        return DEFAULTS.get(type);
    }

    public EmailContent orderCreated(Order order) {
        Map<String, String> params = new HashMap<>();
        params.put("customerName", order.getUser().getFullName());
        params.put("orderCode", order.getOrderCode());
        params.put("totalAmount", order.getTotalAmount().toString());
        params.put("paymentMethod", order.getPaymentMethod().toString());
        return render(NotificationType.ORDER_CREATED, params);
    }

    public EmailContent orderCancelled(Order order) {
        Map<String, String> params = new HashMap<>();
        params.put("customerName", order.getUser().getFullName());
        params.put("orderCode", order.getOrderCode());
        params.put("totalAmount", order.getTotalAmount().toString());
        return render(NotificationType.ORDER_CANCELLED, params);
    }

    public EmailContent adminOrderCreated(Order order) {
        Map<String, String> params = orderParams(order);
        params.put("paymentMethod", order.getPaymentMethod().toString());
        return render(NotificationType.ADMIN_ORDER_CREATED, params);
    }

    public EmailContent adminOrderCancelled(Order order) {
        return render(NotificationType.ADMIN_ORDER_CANCELLED, orderParams(order));
    }

    public EmailContent cancellationApproved(Order order) {
        return render(NotificationType.CANCELLATION_APPROVED, orderParams(order));
    }

    public EmailContent cancellationRejected(Order order, String reason) {
        Map<String, String> params = orderParams(order);
        params.put("rejectionReason", reason == null || reason.isBlank()
                ? "Yêu cầu chưa đáp ứng điều kiện hủy đơn" : reason.trim());
        return render(NotificationType.CANCELLATION_REJECTED, params);
    }

    private Map<String, String> orderParams(Order order) {
        Map<String, String> params = new HashMap<>();
        params.put("customerName", order.getUser().getFullName());
        params.put("orderCode", order.getOrderCode());
        params.put("totalAmount", order.getTotalAmount().toString());
        return params;
    }

    public EmailContent orderDelivered(Order order) {
        Map<String, String> params = new HashMap<>();
        params.put("customerName", order.getUser().getFullName());
        params.put("orderCode", order.getOrderCode());
        params.put("totalAmount", order.getTotalAmount().toString());
        return render(NotificationType.ORDER_DELIVERED, params);
    }

    public EmailContent orderShipping(Order order) {
        Map<String, String> params = new HashMap<>();
        params.put("customerName", order.getUser().getFullName());
        params.put("orderCode", order.getOrderCode());
        params.put("totalAmount", order.getTotalAmount().toString());
        return render(NotificationType.ORDER_SHIPPING, params);
    }

    public EmailContent orderStatusUpdated(Order order, String oldStatus, String newStatus) {
        Map<String, String> params = new HashMap<>();
        params.put("customerName", order.getUser().getFullName());
        params.put("orderCode", order.getOrderCode());
        params.put("oldStatus", oldStatus);
        params.put("newStatus", newStatus);
        return render(NotificationType.ORDER_STATUS_UPDATED, params);
    }

    public EmailContent refundCompleted(Order order, String refundCode, BigDecimal refundAmount) {
        Map<String, String> params = orderParams(order);
        params.put("refundCode", refundCode);
        params.put("refundAmount", refundAmount.toPlainString());
        return render(NotificationType.REFUND_COMPLETED, params);
    }

    public EmailContent paymentCompleted(Order order, BigDecimal paymentAmount) {
        Map<String, String> params = orderParams(order);
        params.put("paymentAmount", paymentAmount.toPlainString());
        return render(NotificationType.PAYMENT_COMPLETED, params);
    }

    public EmailContent passwordReset(String resetLink, int ttlMinutes) {
        Map<String, String> params = new HashMap<>();
        params.put("resetLink", resetLink);
        params.put("ttlMinutes", String.valueOf(ttlMinutes));
        return render(NotificationType.PASSWORD_RESET, params);
    }

    private EmailContent render(NotificationType type, Map<String, String> params) {
        EmailContent template = templateRepository.findByType(type)
                .map(t -> new EmailContent(t.getSubject(), t.getBody()))
                .orElseGet(() -> DEFAULTS.get(type));
        return new EmailContent(substitute(template.subject(), params), substitute(template.body(), params));
    }

    private String substitute(String text, Map<String, String> params) {
        String result = text;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
