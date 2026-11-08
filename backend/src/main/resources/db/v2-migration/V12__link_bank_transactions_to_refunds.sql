alter table bank_transactions
    add column refund_id uuid;

alter table bank_transactions
    add constraint fk_bank_transactions_refund
        foreign key (refund_id) references refunds(id) on delete restrict;

create index idx_bank_transactions_refund_received
    on bank_transactions(refund_id, received_at desc)
    where refund_id is not null;

create unique index uq_bank_transactions_matched_refund
    on bank_transactions(refund_id)
    where refund_id is not null and match_status in ('MATCHED', 'MANUALLY_MATCHED');

comment on column bank_transactions.refund_id is
    'Refund matched to a signed SePay outgoing transaction. Recipient details remain on the refund record.';
