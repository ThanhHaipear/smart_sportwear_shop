alter table refunds
    add column refund_bank_code varchar(20),
    add column refund_bank_name varchar(100),
    add column refund_account_number varchar(30),
    add column refund_account_name varchar(100);

alter table refunds
    add constraint chk_refunds_destination_complete check (
        (refund_bank_code is null
            and refund_bank_name is null
            and refund_account_number is null
            and refund_account_name is null)
        or
        (nullif(btrim(refund_bank_code), '') is not null
            and nullif(btrim(refund_bank_name), '') is not null
            and nullif(btrim(refund_account_number), '') is not null
            and nullif(btrim(refund_account_name), '') is not null)
    );

comment on column refunds.refund_bank_code is
    'Customer-selected bank code for a manual refund destination.';
comment on column refunds.refund_bank_name is
    'Customer-visible bank name captured when the refund is requested.';
comment on column refunds.refund_account_number is
    'Bank account receiving a manual refund; access is restricted to the owning customer and staff.';
comment on column refunds.refund_account_name is
    'Beneficiary name captured and confirmed by staff before transferring a manual refund.';
