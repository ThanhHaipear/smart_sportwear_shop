alter table orders
    add column if not exists data_source varchar(20) not null default 'REAL';

alter table orders
    drop constraint if exists orders_data_source_check;

alter table orders
    add constraint orders_data_source_check
    check (data_source in ('DEMO', 'REAL', 'IMPORTED'));

update orders
set data_source = 'DEMO'
where note like '[SEED]%';

create index if not exists idx_orders_data_source_created_at
    on orders(data_source, created_at);
