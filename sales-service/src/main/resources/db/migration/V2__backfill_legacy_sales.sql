-- Idempotent copy from legacy public tables. Source tables remain intact for rollback.
do $$ begin
if to_regclass('public.carts') is not null then
 insert into sales.carts select (jsonb_populate_record(null::sales.carts,to_jsonb(p)||jsonb_build_object('user_ref',p.user_id))).* from public.carts p on conflict do nothing;
end if;
if to_regclass('public.cart_items') is not null then
 insert into sales.cart_items select (jsonb_populate_record(null::sales.cart_items,to_jsonb(p)||jsonb_build_object('variant_ref',p.variant_id))).* from public.cart_items p on conflict do nothing;
end if;
if to_regclass('public.orders') is not null then
 insert into sales.orders select (jsonb_populate_record(null::sales.orders,to_jsonb(p)||jsonb_build_object('user_ref',p.user_id,'payment_method',case when p.payment_method='VNPAY' then 'VIETQR' else p.payment_method end))).* from public.orders p on conflict do nothing;
end if;
if to_regclass('public.order_items') is not null then
 insert into sales.order_items select (jsonb_populate_record(null::sales.order_items,to_jsonb(p)||jsonb_build_object('product_ref',p.product_id,'variant_ref',p.variant_id))).* from public.order_items p on conflict do nothing;
end if;
if to_regclass('public.order_status_history') is not null then
 insert into sales.order_status_history select (jsonb_populate_record(null::sales.order_status_history,to_jsonb(p)||jsonb_build_object('actor_ref',p.actor_user_id))).* from public.order_status_history p on conflict do nothing;
end if;
if to_regclass('public.payments') is not null then
 insert into sales.payments select (jsonb_populate_record(null::sales.payments,to_jsonb(p)||jsonb_build_object('provider',case when p.provider='VNPAY' then 'BANK_TRANSFER' else p.provider end))).* from public.payments p on conflict do nothing;
end if;
if to_regclass('public.payment_events') is not null then
 insert into sales.payment_events select (jsonb_populate_record(null::sales.payment_events,to_jsonb(p)||jsonb_build_object('provider',case when p.provider='VNPAY' then 'BANK_TRANSFER' else p.provider end))).* from public.payment_events p on conflict do nothing;
end if;
if to_regclass('public.returns') is not null then
 insert into sales.returns select (jsonb_populate_record(null::sales.returns,to_jsonb(p)||jsonb_build_object('user_ref',p.user_id,'handled_by_ref',p.handled_by))).* from public.returns p on conflict do nothing;
end if;
if to_regclass('public.return_items') is not null then insert into sales.return_items select (jsonb_populate_record(null::sales.return_items,to_jsonb(p))).* from public.return_items p on conflict do nothing; end if;
if to_regclass('public.refunds') is not null then
 insert into sales.refunds select (jsonb_populate_record(null::sales.refunds,to_jsonb(p)||jsonb_build_object('created_by_ref',p.created_by,'provider',case when p.provider='VNPAY' then 'BANK_TRANSFER' else p.provider end))).* from public.refunds p on conflict do nothing;
end if;
if to_regclass('public.bank_transactions') is not null then insert into sales.bank_transactions select (jsonb_populate_record(null::sales.bank_transactions,to_jsonb(p))).* from public.bank_transactions p on conflict do nothing; end if;
if to_regclass('public.shipping_methods') is not null then insert into sales.shipping_methods select (jsonb_populate_record(null::sales.shipping_methods,to_jsonb(p))).* from public.shipping_methods p on conflict do nothing; end if;
if to_regclass('public.shipments') is not null then insert into sales.shipments select (jsonb_populate_record(null::sales.shipments,to_jsonb(p))).* from public.shipments p on conflict do nothing; end if;
if to_regclass('public.notifications') is not null then
 insert into sales.notifications select (jsonb_populate_record(null::sales.notifications,to_jsonb(p)||jsonb_build_object('user_ref',p.user_id))).* from public.notifications p on conflict do nothing;
end if;
end $$;
