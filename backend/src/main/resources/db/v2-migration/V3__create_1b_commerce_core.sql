-- Database V2 - batch 1B
-- Cart, order, payment, return/refund, inventory, operations and interaction facts.

set search_path = public;

-- ============================================================================
-- 1B.1 Cart
-- ============================================================================

create table carts (
    id uuid primary key default gen_random_uuid(),
    user_id uuid,
    session_id varchar(255),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_carts_user foreign key (user_id) references users (id) on delete cascade,
    constraint chk_carts_owner check (num_nonnulls(user_id, session_id) = 1),
    constraint chk_carts_session check (session_id is null or btrim(session_id) <> '')
);

create unique index uq_carts_user on carts (user_id) where user_id is not null;
create unique index uq_carts_session on carts (session_id) where session_id is not null;

create table cart_items (
    id uuid primary key default gen_random_uuid(),
    cart_id uuid not null,
    variant_id uuid not null,
    quantity integer not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_cart_items_cart foreign key (cart_id) references carts (id) on delete cascade,
    constraint fk_cart_items_variant foreign key (variant_id) references product_variants (id) on delete restrict,
    constraint uq_cart_items_cart_variant unique (cart_id, variant_id),
    constraint chk_cart_items_quantity check (quantity > 0)
);

create index idx_cart_items_variant on cart_items (variant_id);

-- ============================================================================
-- 1B.2 Order and immutable snapshots
-- ============================================================================

create sequence orders_invoice_number_seq start with 1;

create table orders (
    id uuid primary key default gen_random_uuid(),
    order_code varchar(50) not null,
    user_id uuid not null,
    address_snapshot_json jsonb not null,
    subtotal_amount numeric(14,2) not null,
    shipping_fee numeric(14,2) not null default 0,
    discount_amount numeric(14,2) not null default 0,
    total_amount numeric(14,2) not null,
    currency_code char(3) not null default 'VND',
    payment_method varchar(20) not null,
    order_status varchar(30) not null default 'PENDING_CONFIRMATION',
    payment_status varchar(30) not null default 'UNPAID',
    note text,
    internal_note text,
    cancellation_requested_by varchar(20),
    cancellation_reason text,
    cancellation_requested_at timestamptz,
    data_source varchar(20) not null,
    placed_at timestamptz not null default now(),
    delivered_at timestamptz,
    return_eligible_until timestamptz,
    invoice_number varchar(30),
    invoice_requested boolean not null default false,
    invoice_company_name varchar(200),
    invoice_tax_code varchar(50),
    invoice_company_address varchar(255),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_orders_user foreign key (user_id) references users (id) on delete restrict,
    constraint uq_orders_order_code unique (order_code),
    constraint uq_orders_invoice_number unique (invoice_number),
    constraint chk_orders_money check (
        subtotal_amount >= 0 and shipping_fee >= 0 and discount_amount >= 0
        and discount_amount <= subtotal_amount + shipping_fee
        and total_amount = subtotal_amount + shipping_fee - discount_amount
    ),
    constraint chk_orders_currency check (currency_code = 'VND'),
    constraint chk_orders_payment_method check (payment_method in ('COD', 'VNPAY')),
    constraint chk_orders_order_status check (
        order_status in ('PENDING_CONFIRMATION', 'CONFIRMED', 'PACKING', 'SHIPPING', 'DELIVERED', 'CANCELLED')
    ),
    constraint chk_orders_payment_status check (
        payment_status in ('UNPAID', 'PENDING', 'PAID', 'FAILED', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED')
    ),
    constraint chk_orders_data_source check (data_source in ('REAL', 'DEMO', 'SYNTHETIC')),
    constraint chk_orders_address_snapshot check (jsonb_typeof(address_snapshot_json) = 'object'),
    constraint chk_orders_delivery_window check (
        (delivered_at is null and return_eligible_until is null)
        or (delivered_at is not null and return_eligible_until = delivered_at + interval '7 days')
    ),
    constraint chk_orders_invoice_company check (
        not invoice_requested
        or (nullif(btrim(invoice_company_name), '') is not null
            and nullif(btrim(invoice_tax_code), '') is not null
            and nullif(btrim(invoice_company_address), '') is not null)
    )
);

create index idx_orders_user_placed on orders (user_id, placed_at desc);
create index idx_orders_status_placed on orders (order_status, placed_at desc);
create index idx_orders_payment_status on orders (payment_status, placed_at desc);
create index idx_orders_return_window on orders (return_eligible_until) where return_eligible_until is not null;

create table order_items (
    id uuid primary key default gen_random_uuid(),
    order_id uuid not null,
    product_id uuid not null,
    variant_id uuid not null,
    product_name_snapshot varchar(255) not null,
    sku_snapshot varchar(100) not null,
    size_snapshot varchar(100) not null,
    color_snapshot varchar(100) not null,
    unit_price_snapshot numeric(14,2) not null,
    quantity integer not null,
    line_total numeric(14,2) not null,
    discount_allocated numeric(14,2) not null default 0,
    net_line_amount numeric(14,2) not null,
    return_pending_quantity integer not null default 0,
    returned_quantity integer not null default 0,
    constraint fk_order_items_order foreign key (order_id) references orders (id) on delete restrict,
    constraint fk_order_items_product foreign key (product_id) references products (id) on delete restrict,
    constraint fk_order_items_product_variant foreign key (product_id, variant_id)
        references product_variants (product_id, id) on delete restrict,
    constraint uq_order_items_id_order unique (id, order_id),
    constraint chk_order_items_quantity check (quantity > 0),
    constraint chk_order_items_price check (unit_price_snapshot >= 0),
    constraint chk_order_items_line_total check (line_total = unit_price_snapshot * quantity),
    constraint chk_order_items_discount check (discount_allocated >= 0 and discount_allocated <= line_total),
    constraint chk_order_items_net check (net_line_amount = line_total - discount_allocated),
    constraint chk_order_items_return_quantity check (
        return_pending_quantity >= 0 and returned_quantity >= 0
        and return_pending_quantity + returned_quantity <= quantity
    )
);

create index idx_order_items_order on order_items (order_id);
create index idx_order_items_variant on order_items (variant_id);

create table order_status_history (
    id uuid primary key default gen_random_uuid(),
    order_id uuid not null,
    from_status varchar(30),
    to_status varchar(30) not null,
    reason text,
    actor_user_id uuid,
    occurred_at timestamptz not null default now(),
    constraint fk_order_status_history_order foreign key (order_id) references orders (id) on delete restrict,
    constraint fk_order_status_history_actor foreign key (actor_user_id) references users (id) on delete restrict,
    constraint chk_order_status_history_from check (
        from_status is null or from_status in ('PENDING_CONFIRMATION', 'CONFIRMED', 'PACKING', 'SHIPPING', 'DELIVERED', 'CANCELLED')
    ),
    constraint chk_order_status_history_to check (
        to_status in ('PENDING_CONFIRMATION', 'CONFIRMED', 'PACKING', 'SHIPPING', 'DELIVERED', 'CANCELLED')
    ),
    constraint chk_order_status_history_changed check (from_status is null or from_status <> to_status)
);

create index idx_order_status_history_order_time on order_status_history (order_id, occurred_at desc);

-- ============================================================================
-- 1B.3 Payment and provider events
-- ============================================================================

create table payments (
    id uuid primary key default gen_random_uuid(),
    order_id uuid not null,
    provider varchar(20) not null,
    transaction_ref varchar(150) not null,
    amount numeric(14,2) not null,
    status varchar(30) not null,
    raw_payload_json jsonb,
    gateway_transaction_no varchar(100),
    bank_code varchar(30),
    transaction_date varchar(14),
    expires_at timestamptz,
    paid_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_payments_order foreign key (order_id) references orders (id) on delete restrict,
    constraint uq_payments_provider_ref unique (provider, transaction_ref),
    constraint uq_payments_id_order unique (id, order_id),
    constraint chk_payments_provider check (provider in ('COD', 'VNPAY')),
    constraint chk_payments_status check (
        status in ('UNPAID', 'PENDING', 'PAID', 'FAILED', 'CANCELLED', 'PARTIALLY_REFUNDED', 'REFUNDED')
    ),
    constraint chk_payments_amount check (amount > 0),
    constraint chk_payments_paid_at check (
        status not in ('PAID', 'PARTIALLY_REFUNDED', 'REFUNDED') or paid_at is not null
    ),
    constraint chk_payments_expiry check (expires_at is null or expires_at > created_at)
);

create unique index uq_payments_active_provider_order
    on payments (order_id, provider) where status = 'PENDING';
create index idx_payments_order_created on payments (order_id, created_at desc);
create index idx_payments_status_expiry on payments (status, expires_at) where status = 'PENDING';

create table payment_events (
    id uuid primary key default gen_random_uuid(),
    payment_id uuid not null,
    provider varchar(20) not null,
    provider_event_key char(64) not null,
    event_type varchar(50) not null,
    verified boolean not null,
    payload_redacted jsonb,
    received_at timestamptz not null default now(),
    constraint fk_payment_events_payment foreign key (payment_id) references payments (id) on delete restrict,
    constraint uq_payment_events_provider_key unique (provider, provider_event_key),
    constraint chk_payment_events_provider check (provider in ('COD', 'VNPAY')),
    constraint chk_payment_events_key check (provider_event_key ~ '^[0-9a-f]{64}$'),
    constraint chk_payment_events_payload check (payload_redacted is null or jsonb_typeof(payload_redacted) = 'object')
);

create index idx_payment_events_payment_time on payment_events (payment_id, received_at desc);

-- ============================================================================
-- 1B.4 Returns and refunds
-- ============================================================================

create table returns (
    id uuid primary key default gen_random_uuid(),
    order_id uuid not null,
    user_id uuid not null,
    return_code varchar(50) not null,
    status varchar(30) not null default 'REQUESTED',
    reason varchar(100) not null,
    description text,
    requested_at timestamptz not null default now(),
    approved_at timestamptz,
    received_at timestamptz,
    resolved_at timestamptz,
    handled_by uuid,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_returns_order foreign key (order_id) references orders (id) on delete restrict,
    constraint fk_returns_requested_by foreign key (user_id) references users (id) on delete restrict,
    constraint fk_returns_handled_by foreign key (handled_by) references users (id) on delete restrict,
    constraint uq_returns_code unique (return_code),
    constraint uq_returns_id_order unique (id, order_id),
    constraint chk_returns_status check (status in ('REQUESTED', 'APPROVED', 'RECEIVED', 'REFUNDED', 'REJECTED', 'CANCELLED')),
    constraint chk_returns_reason check (reason in ('WRONG_SIZE', 'DAMAGED', 'DEFECTIVE', 'WRONG_ITEM', 'NO_LONGER_NEEDED', 'OTHER')),
    constraint chk_returns_times check (
        (approved_at is null or approved_at >= requested_at)
        and (received_at is null or received_at >= requested_at)
        and (resolved_at is null or resolved_at >= requested_at)
    )
);

create unique index uq_returns_one_open_per_order
    on returns (order_id) where status in ('REQUESTED', 'APPROVED', 'RECEIVED');
create index idx_returns_user_time on returns (user_id, requested_at desc);
create index idx_returns_status_time on returns (status, requested_at);

create table return_items (
    id uuid primary key default gen_random_uuid(),
    return_id uuid not null,
    order_id uuid not null,
    order_item_id uuid not null,
    quantity integer not null,
    reason varchar(100),
    condition_status varchar(30),
    resolution varchar(30),
    refund_amount numeric(14,2),
    restocked boolean not null default false,
    created_at timestamptz not null default now(),
    constraint fk_return_items_return foreign key (return_id, order_id)
        references returns (id, order_id) on delete restrict,
    constraint fk_return_items_order_item foreign key (order_item_id, order_id)
        references order_items (id, order_id) on delete restrict,
    constraint uq_return_items_return_order_item unique (return_id, order_item_id),
    constraint chk_return_items_quantity check (quantity > 0),
    constraint chk_return_items_condition check (
        condition_status is null or condition_status in ('UNOPENED', 'LIKE_NEW', 'USED', 'DAMAGED')
    ),
    constraint chk_return_items_resolution check (resolution is null or resolution in ('REFUND', 'REJECT')),
    constraint chk_return_items_refund check (
        (resolution = 'REFUND' and refund_amount is not null and refund_amount > 0)
        or (resolution is distinct from 'REFUND' and refund_amount is null)
    ),
    constraint chk_return_items_restock check (
        not restocked or (resolution = 'REFUND' and condition_status in ('UNOPENED', 'LIKE_NEW'))
    )
);

create table refunds (
    id uuid primary key default gen_random_uuid(),
    return_id uuid,
    order_id uuid not null,
    payment_id uuid,
    refund_code varchar(50) not null,
    amount numeric(14,2) not null,
    shipping_refund_amount numeric(14,2) not null default 0,
    provider varchar(20) not null,
    status varchar(20) not null default 'PENDING',
    reason text,
    gateway_request_id varchar(64),
    gateway_transaction_no varchar(100),
    gateway_response_json jsonb,
    manual_reference varchar(100),
    manual_note text,
    refunded_at timestamptz,
    created_by uuid,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_refunds_order foreign key (order_id) references orders (id) on delete restrict,
    constraint fk_refunds_payment_order foreign key (payment_id, order_id)
        references payments (id, order_id) on delete restrict,
    constraint fk_refunds_return_order foreign key (return_id, order_id)
        references returns (id, order_id) on delete restrict,
    constraint fk_refunds_created_by foreign key (created_by) references users (id) on delete restrict,
    constraint uq_refunds_code unique (refund_code),
    constraint uq_refunds_gateway_request unique (gateway_request_id),
    constraint chk_refunds_amount check (amount > 0 and shipping_refund_amount >= 0 and shipping_refund_amount <= amount),
    constraint chk_refunds_provider check (provider in ('COD', 'VNPAY', 'MANUAL')),
    constraint chk_refunds_status check (status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    constraint chk_refunds_completed_at check (status <> 'COMPLETED' or refunded_at is not null)
);

create unique index uq_refunds_active_cancellation_payment
    on refunds (payment_id) where return_id is null and status in ('PENDING', 'PROCESSING', 'COMPLETED');
create unique index uq_refunds_active_return
    on refunds (return_id) where return_id is not null and status in ('PENDING', 'PROCESSING', 'COMPLETED');
create index idx_refunds_order_time on refunds (order_id, created_at desc);

-- ============================================================================
-- 1B.5 Inventory ledger
-- ============================================================================

create table inventory_transactions (
    id uuid primary key default gen_random_uuid(),
    variant_id uuid not null,
    order_id uuid,
    return_id uuid,
    type varchar(30) not null,
    quantity integer not null,
    before_stock_quantity integer not null,
    after_stock_quantity integer not null,
    before_reserved_quantity integer not null,
    after_reserved_quantity integer not null,
    actor_user_id uuid,
    reason text,
    idempotency_key varchar(200) not null,
    created_at timestamptz not null default now(),
    constraint fk_inventory_variant foreign key (variant_id) references product_variants (id) on delete restrict,
    constraint fk_inventory_order foreign key (order_id) references orders (id) on delete restrict,
    constraint fk_inventory_return foreign key (return_id) references returns (id) on delete restrict,
    constraint fk_inventory_actor foreign key (actor_user_id) references users (id) on delete restrict,
    constraint uq_inventory_idempotency unique (idempotency_key),
    constraint chk_inventory_type check (type in (
        'IMPORT', 'EXPORT', 'ADJUSTMENT_UP', 'ADJUSTMENT_DOWN', 'ORDER_RESERVE',
        'ORDER_RELEASE', 'ORDER_CONFIRM_DEDUCT', 'ORDER_CANCEL_RESTOCK', 'RETURN_RESTOCK'
    )),
    constraint chk_inventory_quantities check (
        quantity > 0
        and before_stock_quantity >= 0 and after_stock_quantity >= 0
        and before_reserved_quantity >= 0 and after_reserved_quantity >= 0
        and before_reserved_quantity <= before_stock_quantity
        and after_reserved_quantity <= after_stock_quantity
    ),
    constraint chk_inventory_source check (
        (type in ('IMPORT', 'EXPORT', 'ADJUSTMENT_UP', 'ADJUSTMENT_DOWN')
            and actor_user_id is not null and nullif(btrim(reason), '') is not null
            and order_id is null and return_id is null)
        or (type in ('ORDER_RESERVE', 'ORDER_RELEASE', 'ORDER_CONFIRM_DEDUCT', 'ORDER_CANCEL_RESTOCK')
            and order_id is not null and return_id is null)
        or (type = 'RETURN_RESTOCK' and return_id is not null)
    )
);

create index idx_inventory_variant_time on inventory_transactions (variant_id, created_at desc);
create index idx_inventory_order on inventory_transactions (order_id) where order_id is not null;
create index idx_inventory_return on inventory_transactions (return_id) where return_id is not null;

-- ============================================================================
-- 1B.6 Settings, audit, outbox and interaction facts
-- ============================================================================

create table site_settings (
    id uuid primary key default gen_random_uuid(),
    setting_key varchar(120) not null,
    setting_value text,
    value_type varchar(20) not null default 'STRING',
    description text,
    is_public boolean not null default false,
    updated_by uuid,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_site_settings_updated_by foreign key (updated_by) references users (id) on delete restrict,
    constraint uq_site_settings_key unique (setting_key),
    constraint chk_site_settings_key check (setting_key = lower(btrim(setting_key)) and setting_key <> ''),
    constraint chk_site_settings_type check (value_type in ('STRING', 'NUMBER', 'BOOLEAN', 'JSON'))
);

create table audit_logs (
    id uuid primary key default gen_random_uuid(),
    actor_user_id uuid,
    action varchar(100) not null,
    entity_type varchar(100) not null,
    entity_id varchar(100) not null,
    before_json jsonb,
    after_json jsonb,
    details_redacted jsonb,
    ip_address varchar(64),
    user_agent varchar(500),
    created_at timestamptz not null default now(),
    constraint fk_audit_logs_actor foreign key (actor_user_id) references users (id) on delete restrict,
    constraint chk_audit_logs_action check (btrim(action) <> ''),
    constraint chk_audit_logs_entity check (btrim(entity_type) <> '' and btrim(entity_id) <> '')
);

create index idx_audit_actor_time on audit_logs (actor_user_id, created_at desc);
create index idx_audit_entity_time on audit_logs (entity_type, entity_id, created_at desc);

create table integration_outbox (
    id uuid primary key default gen_random_uuid(),
    idempotency_key varchar(200) not null,
    event_type varchar(100) not null,
    event_version integer not null,
    aggregate_type varchar(100) not null,
    aggregate_id uuid not null,
    aggregate_version bigint not null,
    payload jsonb not null,
    status varchar(20) not null default 'PENDING',
    attempts integer not null default 0,
    available_at timestamptz not null default now(),
    created_at timestamptz not null default now(),
    published_at timestamptz,
    last_error text,
    constraint uq_outbox_idempotency unique (idempotency_key),
    constraint chk_outbox_event_version check (event_version > 0),
    constraint chk_outbox_aggregate_version check (aggregate_version >= 0),
    constraint chk_outbox_payload check (jsonb_typeof(payload) = 'object'),
    constraint chk_outbox_status check (status in ('PENDING', 'PUBLISHING', 'PUBLISHED', 'FAILED')),
    constraint chk_outbox_attempts check (attempts >= 0),
    constraint chk_outbox_published_at check ((status = 'PUBLISHED') = (published_at is not null))
);

create index idx_outbox_available
    on integration_outbox (available_at, created_at) where status in ('PENDING', 'FAILED', 'PUBLISHING');
create index idx_outbox_aggregate on integration_outbox (aggregate_type, aggregate_id, aggregate_version);

create table user_interactions (
    event_id uuid primary key default gen_random_uuid(),
    occurred_at timestamptz not null default now(),
    user_id uuid,
    anonymous_id varchar(100),
    event_type varchar(50) not null,
    product_id uuid,
    variant_id uuid,
    request_id uuid,
    source varchar(50) not null,
    data_source varchar(20) not null,
    metadata jsonb,
    anonymized_at timestamptz,
    constraint fk_interactions_user foreign key (user_id) references users (id) on delete set null,
    constraint fk_interactions_product foreign key (product_id) references products (id) on delete restrict,
    constraint fk_interactions_product_variant foreign key (product_id, variant_id)
        references product_variants (product_id, id) on delete restrict,
    constraint chk_interactions_identity check (num_nonnulls(user_id, anonymous_id) >= 1),
    constraint chk_interactions_event check (event_type in (
        'VIEW', 'SEARCH_IMPRESSION', 'SEARCH_CLICK', 'RECOMMENDATION_IMPRESSION',
        'RECOMMENDATION_CLICK', 'ADD_TO_CART', 'WISHLIST', 'PURCHASE'
    )),
    constraint chk_interactions_source check (btrim(source) <> ''),
    constraint chk_interactions_data_source check (data_source in ('REAL', 'DEMO', 'SYNTHETIC')),
    constraint chk_interactions_metadata check (metadata is null or jsonb_typeof(metadata) = 'object'),
    constraint chk_interactions_anonymized check (anonymized_at is null or user_id is null)
);

create index idx_interactions_time on user_interactions (occurred_at);
create index idx_interactions_user_time on user_interactions (user_id, occurred_at desc) where user_id is not null;
create index idx_interactions_type_time on user_interactions (event_type, occurred_at desc);

-- ============================================================================
-- Cross-row protection and lifecycle triggers
-- ============================================================================

create or replace function reject_fact_mutation()
returns trigger
language plpgsql
as $$
begin
    raise exception using errcode = '55000', message = format('%I is append-only', tg_table_name);
end;
$$;

create trigger trg_order_status_history_append_only
before update or delete on order_status_history for each row execute function reject_fact_mutation();
create trigger trg_payment_events_append_only
before update or delete on payment_events for each row execute function reject_fact_mutation();
create trigger trg_inventory_transactions_append_only
before update or delete on inventory_transactions for each row execute function reject_fact_mutation();
create trigger trg_audit_logs_append_only
before update or delete on audit_logs for each row execute function reject_fact_mutation();
create trigger trg_user_interactions_append_only
before update or delete on user_interactions for each row execute function reject_fact_mutation();

create or replace function protect_commerce_data_source()
returns trigger
language plpgsql
as $$
begin
    if new.data_source is distinct from old.data_source then
        raise exception using errcode = '23514', message = format('%I.data_source is immutable', tg_table_name);
    end if;
    return new;
end;
$$;

create trigger trg_orders_data_source_immutable
before update of data_source on orders for each row execute function protect_commerce_data_source();

create or replace function validate_inventory_delta()
returns trigger
language plpgsql
as $$
begin
    if new.type = 'IMPORT' and not (
        new.after_stock_quantity = new.before_stock_quantity + new.quantity
        and new.after_reserved_quantity = new.before_reserved_quantity
    ) then raise exception using errcode = '23514', message = 'Invalid IMPORT movement';
    elsif new.type in ('EXPORT', 'ADJUSTMENT_DOWN') and not (
        new.after_stock_quantity = new.before_stock_quantity - new.quantity
        and new.after_reserved_quantity = new.before_reserved_quantity
    ) then raise exception using errcode = '23514', message = 'Invalid stock decrease movement';
    elsif new.type = 'ADJUSTMENT_UP' and not (
        new.after_stock_quantity = new.before_stock_quantity + new.quantity
        and new.after_reserved_quantity = new.before_reserved_quantity
    ) then raise exception using errcode = '23514', message = 'Invalid ADJUSTMENT_UP movement';
    elsif new.type = 'ORDER_RESERVE' and not (
        new.after_stock_quantity = new.before_stock_quantity
        and new.after_reserved_quantity = new.before_reserved_quantity + new.quantity
    ) then raise exception using errcode = '23514', message = 'Invalid ORDER_RESERVE movement';
    elsif new.type = 'ORDER_RELEASE' and not (
        new.after_stock_quantity = new.before_stock_quantity
        and new.after_reserved_quantity = new.before_reserved_quantity - new.quantity
    ) then raise exception using errcode = '23514', message = 'Invalid ORDER_RELEASE movement';
    elsif new.type = 'ORDER_CONFIRM_DEDUCT' and not (
        new.after_stock_quantity = new.before_stock_quantity - new.quantity
        and new.after_reserved_quantity = new.before_reserved_quantity - new.quantity
    ) then raise exception using errcode = '23514', message = 'Invalid ORDER_CONFIRM_DEDUCT movement';
    elsif new.type in ('ORDER_CANCEL_RESTOCK', 'RETURN_RESTOCK') and not (
        new.after_stock_quantity = new.before_stock_quantity + new.quantity
        and new.after_reserved_quantity = new.before_reserved_quantity
    ) then raise exception using errcode = '23514', message = 'Invalid restock movement';
    end if;
    return new;
end;
$$;

create trigger trg_inventory_transactions_delta
before insert on inventory_transactions for each row execute function validate_inventory_delta();

create trigger trg_carts_updated_at before update on carts for each row execute function set_updated_at();
create trigger trg_cart_items_updated_at before update on cart_items for each row execute function set_updated_at();
create trigger trg_orders_updated_at before update on orders for each row execute function set_updated_at();
create trigger trg_payments_updated_at before update on payments for each row execute function set_updated_at();
create trigger trg_returns_updated_at before update on returns for each row execute function set_updated_at();
create trigger trg_refunds_updated_at before update on refunds for each row execute function set_updated_at();
create trigger trg_site_settings_updated_at before update on site_settings for each row execute function set_updated_at();
