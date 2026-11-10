-- Idempotent copy; legacy tables remain the rollback source until cut-over is accepted.
do $$ begin
if to_regclass('public.users') is not null then
  insert into identity.users(id,email,password_hash,full_name,phone,role_code,status,last_login_at,anonymized_at,created_at,updated_at)
  select id,email,password_hash,full_name,phone,role_code,status,last_login_at,anonymized_at,created_at,updated_at from public.users on conflict(id) do nothing;
end if;
if to_regclass('public.user_identities') is not null then
  insert into identity.user_identities(id,user_id,provider,provider_subject,provider_email,last_login_at,created_at,updated_at)
  select id,user_id,provider,provider_subject,provider_email,last_login_at,created_at,updated_at from public.user_identities on conflict(id) do nothing;
end if;
if to_regclass('public.addresses') is not null then
  insert into identity.addresses(id,user_id,receiver_name,phone,province_name,district_name,ward_name,address_line,is_default,created_at,updated_at)
  select id,user_id,receiver_name,phone,province_name,district_name,ward_name,address_line,is_default,created_at,updated_at from public.addresses on conflict(id) do nothing;
end if;
if to_regclass('public.refresh_tokens') is not null then
  insert into identity.refresh_tokens(id,user_id,token_hash,expires_at,revoked_at,revocation_reason,created_at)
  select id,user_id,token_hash,expires_at,revoked_at,revocation_reason,created_at from public.refresh_tokens on conflict(id) do nothing;
end if;
if to_regclass('public.password_reset_tokens') is not null then
  insert into identity.password_reset_tokens(id,user_id,token_hash,expires_at,used_at,created_at)
  select id,user_id,token_hash,expires_at,used_at,created_at from public.password_reset_tokens on conflict(id) do nothing;
end if;
end $$;
