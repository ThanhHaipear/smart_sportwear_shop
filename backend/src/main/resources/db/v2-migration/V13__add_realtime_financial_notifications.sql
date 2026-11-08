alter table notifications drop constraint chk_notifications_type;
alter table notifications add constraint chk_notifications_type check (type in (
    'ORDER_CREATED', 'ORDER_CANCELLED', 'ORDER_DELIVERED', 'ORDER_SHIPPING',
    'ORDER_STATUS_UPDATED', 'ADMIN_ORDER_CREATED', 'ADMIN_ORDER_CANCELLED',
    'CANCELLATION_APPROVED', 'CANCELLATION_REJECTED', 'PAYMENT_COMPLETED', 'REFUND_COMPLETED',
    'PASSWORD_RESET'
));

alter table notification_templates drop constraint chk_notification_templates_type;
alter table notification_templates add constraint chk_notification_templates_type check (type in (
    'ORDER_CREATED', 'ORDER_CANCELLED', 'ORDER_DELIVERED', 'ORDER_SHIPPING',
    'ORDER_STATUS_UPDATED', 'ADMIN_ORDER_CREATED', 'ADMIN_ORDER_CANCELLED',
    'CANCELLATION_APPROVED', 'CANCELLATION_REJECTED', 'PAYMENT_COMPLETED', 'REFUND_COMPLETED',
    'PASSWORD_RESET'
));
