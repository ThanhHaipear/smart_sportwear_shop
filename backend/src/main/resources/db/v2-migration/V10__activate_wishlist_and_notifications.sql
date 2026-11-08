-- Activate the wishlist and notification modules against the V2 schema.

set search_path = public;

-- ============================================================================
-- Wishlist
-- ============================================================================

create table wishlists (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null,
    name varchar(150) not null default 'Yeu thich',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_wishlists_user foreign key (user_id) references users (id) on delete cascade,
    constraint uq_wishlists_user_id unique (user_id),
    constraint chk_wishlists_name check (btrim(name) <> '')
);

create table wishlist_items (
    id uuid primary key default gen_random_uuid(),
    wishlist_id uuid not null,
    product_id uuid not null,
    created_at timestamptz not null default now(),
    constraint fk_wishlist_items_wishlist foreign key (wishlist_id)
        references wishlists (id) on delete cascade,
    constraint fk_wishlist_items_product foreign key (product_id)
        references products (id) on delete cascade,
    constraint uq_wishlist_items unique (wishlist_id, product_id)
);

create index idx_wishlist_items_product_id on wishlist_items (product_id);

-- ============================================================================
-- Notification history, in-app read state and editable templates
-- ============================================================================

create table notifications (
    id uuid primary key default gen_random_uuid(),
    user_id uuid,
    order_id uuid,
    type varchar(30) not null,
    channel varchar(20) not null default 'EMAIL',
    recipient varchar(255) not null,
    subject varchar(255) not null,
    body text,
    status varchar(20) not null default 'PENDING',
    error_message text,
    sent_at timestamptz,
    read_at timestamptz,
    resend_of_id uuid,
    resend_count integer not null default 0,
    last_resend_at timestamptz,
    created_at timestamptz not null default now(),
    constraint fk_notifications_user foreign key (user_id)
        references users (id) on delete set null,
    constraint fk_notifications_order foreign key (order_id)
        references orders (id) on delete set null,
    constraint fk_notifications_resend_of foreign key (resend_of_id)
        references notifications (id) on delete set null,
    constraint chk_notifications_type check (type in (
        'ORDER_CREATED', 'ORDER_CANCELLED', 'ORDER_DELIVERED', 'ORDER_SHIPPING',
        'ORDER_STATUS_UPDATED', 'ADMIN_ORDER_CREATED', 'ADMIN_ORDER_CANCELLED',
        'CANCELLATION_APPROVED', 'CANCELLATION_REJECTED', 'PASSWORD_RESET'
    )),
    constraint chk_notifications_channel check (channel = 'EMAIL'),
    constraint chk_notifications_status check (status in ('PENDING', 'SENT', 'FAILED')),
    constraint chk_notifications_recipient check (btrim(recipient) <> ''),
    constraint chk_notifications_subject check (btrim(subject) <> ''),
    constraint chk_notifications_resend_count check (resend_count >= 0)
);

create index idx_notifications_user_created on notifications (user_id, created_at desc);
create index idx_notifications_order_created on notifications (order_id, created_at desc);
create index idx_notifications_type_created on notifications (type, created_at desc);
create index idx_notifications_status_created on notifications (status, created_at desc);
create index idx_notifications_resend_of_id on notifications (resend_of_id);
create index idx_notifications_user_unread
    on notifications (user_id, created_at desc) where read_at is null;

create table notification_templates (
    id uuid primary key default gen_random_uuid(),
    type varchar(30) not null,
    channel varchar(20) not null default 'EMAIL',
    subject varchar(255) not null,
    body text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint uq_notification_templates_type unique (type),
    constraint chk_notification_templates_type check (type in (
        'ORDER_CREATED', 'ORDER_CANCELLED', 'ORDER_DELIVERED', 'ORDER_SHIPPING',
        'ORDER_STATUS_UPDATED', 'ADMIN_ORDER_CREATED', 'ADMIN_ORDER_CANCELLED',
        'CANCELLATION_APPROVED', 'CANCELLATION_REJECTED', 'PASSWORD_RESET'
    )),
    constraint chk_notification_templates_channel check (channel = 'EMAIL'),
    constraint chk_notification_templates_subject check (btrim(subject) <> ''),
    constraint chk_notification_templates_body check (btrim(body) <> '')
);
