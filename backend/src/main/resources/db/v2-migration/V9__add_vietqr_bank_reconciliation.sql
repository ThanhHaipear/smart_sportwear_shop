-- VietQR + SePay payment foundation.
-- Keeps legacy VNPay rows readable while allowing new bank-transfer payments
-- and recording every SePay transaction before matching it to an order.

alter table orders drop constraint chk_orders_payment_method;
alter table orders add constraint chk_orders_payment_method
    check (payment_method in ('COD', 'VNPAY', 'VIETQR'));

alter table payments drop constraint chk_payments_provider;
alter table payments add constraint chk_payments_provider
    check (provider in ('COD', 'VNPAY', 'BANK_TRANSFER'));

alter table payment_events drop constraint chk_payment_events_provider;
alter table payment_events add constraint chk_payment_events_provider
    check (provider in ('COD', 'VNPAY', 'BANK_TRANSFER'));

create table bank_transactions (
    id uuid primary key default gen_random_uuid(),
    source varchar(20) not null,
    external_transaction_id varchar(100),
    external_transaction_uuid uuid,
    bank_reference varchar(150),
    account_number varchar(50) not null,
    amount numeric(14,2) not null,
    description text,
    transaction_time timestamptz not null,
    running_balance numeric(18,2),
    payment_id uuid,
    match_status varchar(30) not null default 'RECEIVED',
    match_reason varchar(255),
    raw_payload_redacted jsonb,
    received_at timestamptz not null default now(),
    reconciled_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint fk_bank_transactions_payment
        foreign key (payment_id) references payments(id) on delete restrict,
    constraint chk_bank_transactions_source check (source = 'SEPAY'),
    constraint chk_bank_transactions_external_identity check (
        external_transaction_id is not null or external_transaction_uuid is not null
    ),
    constraint chk_bank_transactions_external_id check (
        external_transaction_id is null or external_transaction_id ~ '^[0-9]+$'
    ),
    constraint chk_bank_transactions_account check (nullif(btrim(account_number), '') is not null),
    constraint chk_bank_transactions_amount check (amount <> 0),
    constraint chk_bank_transactions_match_status check (match_status in (
        'RECEIVED', 'MATCHED', 'UNMATCHED', 'AMOUNT_MISMATCH',
        'AMBIGUOUS', 'LATE_PAYMENT', 'IGNORED_OUTGOING',
        'MANUAL_REVIEW', 'MANUALLY_MATCHED'
    )),
    constraint chk_bank_transactions_payment_link check (
        match_status not in ('MATCHED', 'MANUALLY_MATCHED') or payment_id is not null
    ),
    constraint chk_bank_transactions_payload check (
        raw_payload_redacted is null or jsonb_typeof(raw_payload_redacted) = 'object'
    )
);

create unique index uq_bank_transactions_source_external_id
    on bank_transactions(source, external_transaction_id)
    where external_transaction_id is not null;
create unique index uq_bank_transactions_source_external_uuid
    on bank_transactions(source, external_transaction_uuid)
    where external_transaction_uuid is not null;
create index idx_bank_transactions_cross_channel_merge
    on bank_transactions(source, account_number, bank_reference, amount, transaction_time)
    where bank_reference is not null;
create index idx_bank_transactions_match_received
    on bank_transactions(match_status, received_at desc);
create index idx_bank_transactions_payment_received
    on bank_transactions(payment_id, received_at desc)
    where payment_id is not null;

create table bank_reconciliation_runs (
    id uuid primary key default gen_random_uuid(),
    source varchar(20) not null,
    status varchar(20) not null,
    from_time timestamptz not null,
    to_time timestamptz not null,
    cursor_id uuid,
    records_fetched integer not null default 0,
    records_inserted integer not null default 0,
    records_matched integer not null default 0,
    error_code varchar(100),
    error_message varchar(500),
    started_at timestamptz not null default now(),
    finished_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint chk_bank_reconciliation_runs_source check (source = 'SEPAY'),
    constraint chk_bank_reconciliation_runs_status check (
        status in ('RUNNING', 'SUCCEEDED', 'FAILED', 'PARTIAL')
    ),
    constraint chk_bank_reconciliation_runs_window check (to_time >= from_time),
    constraint chk_bank_reconciliation_runs_counts check (
        records_fetched >= 0 and records_inserted >= 0 and records_matched >= 0
        and records_inserted <= records_fetched
        and records_matched <= records_fetched
    ),
    constraint chk_bank_reconciliation_runs_lifecycle check (
        (status = 'RUNNING' and finished_at is null)
        or (status <> 'RUNNING' and finished_at is not null)
    ),
    constraint chk_bank_reconciliation_runs_error check (
        status not in ('FAILED', 'PARTIAL') or nullif(btrim(error_code), '') is not null
    )
);

create index idx_bank_reconciliation_runs_source_started
    on bank_reconciliation_runs(source, started_at desc);
create index idx_bank_reconciliation_runs_status_started
    on bank_reconciliation_runs(status, started_at desc);

comment on table bank_transactions is
    'Append-preserving SePay bank ledger. Matching state may change; source identifiers and redacted payload remain auditable.';
comment on column bank_transactions.external_transaction_id is
    'Numeric transaction ID delivered by the SePay webhook.';
comment on column bank_transactions.external_transaction_uuid is
    'UUID transaction ID returned by the SePay API v2 reconciliation endpoint.';
comment on table bank_reconciliation_runs is
    'Operational history for SePay API v2 reconciliation runs; secrets and full provider responses are never stored.';
