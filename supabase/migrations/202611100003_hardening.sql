-- StorePrice, этап 4: усиление RLS, прав на функции и RPC.
-- Применять после 202611100001_schema.sql и 202611100002_rls_and_rpc.sql.
-- Миграция идемпотентна: политики пересоздаются, функции заменяются.

-- ---------------------------------------------------------------------------
-- 1. Производительность RLS
-- ---------------------------------------------------------------------------

-- Функции в политиках оборачиваются в scalar subquery, чтобы PostgreSQL
-- вычислял их один раз на запрос, а не для каждой строки.

drop policy if exists profiles_select_self_or_active on public.profiles;
create policy profiles_select_self_or_active
    on public.profiles
    for select
    to authenticated
    using (id = (select auth.uid()) or (select public.current_user_is_active()));

drop policy if exists profiles_update_own_display_name on public.profiles;
create policy profiles_update_own_display_name
    on public.profiles
    for update
    to authenticated
    using (id = (select auth.uid()) and (select public.current_user_is_active()))
    with check (id = (select auth.uid()) and (select public.current_user_is_active()));

drop policy if exists products_select_active on public.products;
create policy products_select_active
    on public.products
    for select
    to authenticated
    using ((select public.current_user_is_active()));

drop policy if exists price_history_select_active on public.price_history;
create policy price_history_select_active
    on public.price_history
    for select
    to authenticated
    using ((select public.current_user_is_active()));

drop policy if exists device_tokens_select_own on public.device_tokens;
create policy device_tokens_select_own
    on public.device_tokens
    for select
    to authenticated
    using (user_id = (select auth.uid()) and (select public.current_user_is_active()));

drop policy if exists device_tokens_insert_own on public.device_tokens;
create policy device_tokens_insert_own
    on public.device_tokens
    for insert
    to authenticated
    with check (user_id = (select auth.uid()) and (select public.current_user_is_active()));

drop policy if exists device_tokens_update_own on public.device_tokens;
create policy device_tokens_update_own
    on public.device_tokens
    for update
    to authenticated
    using (user_id = (select auth.uid()) and (select public.current_user_is_active()))
    with check (user_id = (select auth.uid()) and (select public.current_user_is_active()));

drop policy if exists device_tokens_delete_own on public.device_tokens;
create policy device_tokens_delete_own
    on public.device_tokens
    for delete
    to authenticated
    using (user_id = (select auth.uid()) and (select public.current_user_is_active()));

drop policy if exists audit_log_select_admin on public.audit_log;
create policy audit_log_select_admin
    on public.audit_log
    for select
    to authenticated
    using ((select public.current_user_is_admin()));

-- ---------------------------------------------------------------------------
-- 2. Права на триггерные функции
-- ---------------------------------------------------------------------------

revoke execute on function public.touch_updated_at() from public, anon, authenticated;
revoke execute on function public.set_product_audit_fields() from public, anon, authenticated;
revoke execute on function public.record_price_history() from public, anon, authenticated;
revoke execute on function public.log_product_change() from public, anon, authenticated;
revoke execute on function public.prevent_price_history_mutation()
    from public, anon, authenticated;
revoke execute on function public.handle_new_user() from public, anon, authenticated;

-- ---------------------------------------------------------------------------
-- 3. Токены устройств на общем телефоне
-- ---------------------------------------------------------------------------

create or replace function public.register_device_token(
    p_fcm_token text,
    p_device_id text
)
returns public.device_tokens
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_uid uuid := auth.uid();
    v_fcm_token text := nullif(btrim(p_fcm_token), '');
    v_device_id text := nullif(btrim(p_device_id), '');
    v_token public.device_tokens;
begin
    if not public.current_user_is_active() then
        raise exception using
            errcode = '42501',
            message = 'Учётная запись неактивна или не авторизована';
    end if;

    if v_fcm_token is null or v_device_id is null then
        raise exception using
            errcode = '22023',
            message = 'Токен и идентификатор устройства не могут быть пустыми';
    end if;

    -- Тот же FCM-токен мог остаться у другого сотрудника после смены
    -- пользователя на общем телефоне.
    update public.device_tokens
    set is_active = false
    where is_active
      and fcm_token = v_fcm_token
      and user_id <> v_uid;

    -- У текущего сотрудника на этом устройстве должна остаться одна запись.
    update public.device_tokens
    set is_active = false
    where is_active
      and user_id = v_uid
      and device_id = v_device_id;

    -- Тот же токен на другом устройстве этого сотрудника больше не актуален.
    update public.device_tokens
    set is_active = false
    where is_active
      and user_id = v_uid
      and fcm_token = v_fcm_token;

    select *
    into v_token
    from public.device_tokens
    where user_id = v_uid
      and device_id = v_device_id
    order by updated_at desc, id
    limit 1
    for update;

    if found then
        update public.device_tokens
        set fcm_token = v_fcm_token,
            is_active = true,
            updated_at = now()
        where id = v_token.id
        returning * into v_token;
    else
        insert into public.device_tokens (user_id, fcm_token, device_id, is_active)
        values (v_uid, v_fcm_token, v_device_id, true)
        returning * into v_token;
    end if;

    return v_token;
end;
$$;

comment on function public.register_device_token(text, text) is
    'Регистрирует FCM-токен текущего активного сотрудника; одна активная запись на устройство и токен.';

create or replace function public.unregister_device_token(
    p_device_id text
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_device_id text := nullif(btrim(p_device_id), '');
begin
    if v_device_id is null then
        return;
    end if;

    update public.device_tokens
    set is_active = false
    where user_id = auth.uid()
      and device_id = v_device_id
      and is_active;
end;
$$;

comment on function public.unregister_device_token(text) is
    'Деактивирует FCM-токены текущего сотрудника для указанного устройства.';

revoke all on function public.register_device_token(text, text) from public, anon;
grant execute on function public.register_device_token(text, text) to authenticated;

revoke all on function public.unregister_device_token(text) from public, anon;
grant execute on function public.unregister_device_token(text) to authenticated;

-- ---------------------------------------------------------------------------
-- 4. Ошибки занятого штрихкода в серверных операциях
-- ---------------------------------------------------------------------------

create or replace function public.create_product(
    p_name text,
    p_price_kzt bigint,
    p_barcode text default null,
    p_device_id text default null
)
returns public.products
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_uid uuid := auth.uid();
    v_product public.products;
begin
    if not public.current_user_is_active() then
        raise exception using
            errcode = '42501',
            message = 'Учётная запись неактивна или не авторизована';
    end if;

    if p_name is null or btrim(p_name) = '' then
        raise exception using
            errcode = '22023',
            message = 'Название товара не может быть пустым';
    end if;

    if p_price_kzt is null or p_price_kzt < 0 then
        raise exception using
            errcode = '22023',
            message = 'Цена должна быть неотрицательным целым числом';
    end if;

    perform set_config('storeprice.device_id', coalesce(p_device_id, ''), true);

    begin
        insert into public.products (name, price_kzt, barcode, created_by, updated_by)
        values (
            btrim(p_name),
            p_price_kzt,
            nullif(btrim(p_barcode), ''),
            v_uid,
            v_uid
        )
        returning * into v_product;
    exception
        when unique_violation then
            raise exception using
                errcode = '23505',
                message = 'Штрихкод уже используется активным товаром';
    end;

    return v_product;
end;
$$;

comment on function public.create_product(text, bigint, text, text) is
    'Создаёт товар от имени активного сотрудника; штрихкод нормализуется, версия = 1.';

create or replace function public.update_product(
    p_id uuid,
    p_expected_version integer,
    p_name text,
    p_price_kzt bigint,
    p_barcode text default null,
    p_device_id text default null
)
returns public.products
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_product public.products;
begin
    if not public.current_user_is_active() then
        raise exception using
            errcode = '42501',
            message = 'Учётная запись неактивна или не авторизована';
    end if;

    if p_expected_version is null then
        raise exception using
            errcode = '22023',
            message = 'Не передана ожидаемая версия товара';
    end if;

    if p_name is null or btrim(p_name) = '' then
        raise exception using
            errcode = '22023',
            message = 'Название товара не может быть пустым';
    end if;

    if p_price_kzt is null or p_price_kzt < 0 then
        raise exception using
            errcode = '22023',
            message = 'Цена должна быть неотрицательным целым числом';
    end if;

    perform set_config('storeprice.device_id', coalesce(p_device_id, ''), true);

    select *
    into v_product
    from public.products
    where id = p_id
    for update;

    if not found then
        raise exception using
            errcode = 'P0002',
            message = 'Товар не найден';
    end if;

    if v_product.is_deleted then
        raise exception using
            errcode = '22023',
            message = 'Нельзя изменить удалённый товар';
    end if;

    if v_product.version <> p_expected_version then
        raise exception using
            errcode = '40001',
            message = 'Конфликт версий: товар изменён другим сотрудником';
    end if;

    begin
        update public.products
        set name = btrim(p_name),
            price_kzt = p_price_kzt,
            barcode = nullif(btrim(p_barcode), '')
        where id = p_id
        returning * into v_product;
    exception
        when unique_violation then
            raise exception using
                errcode = '23505',
                message = 'Штрихкод уже используется активным товаром';
    end;

    return v_product;
end;
$$;

comment on function public.update_product(uuid, integer, text, bigint, text, text) is
    'Обновляет товар с проверкой version. История цены и аудит пишутся триггерами.';

create or replace function public.restore_product(
    p_id uuid,
    p_expected_version integer,
    p_device_id text default null
)
returns public.products
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_product public.products;
begin
    if not public.current_user_is_admin() then
        raise exception using
            errcode = '42501',
            message = 'Только администратор может восстанавливать товар';
    end if;

    if p_expected_version is null then
        raise exception using
            errcode = '22023',
            message = 'Не передана ожидаемая версия товара';
    end if;

    perform set_config('storeprice.device_id', coalesce(p_device_id, ''), true);

    select *
    into v_product
    from public.products
    where id = p_id
    for update;

    if not found then
        raise exception using
            errcode = 'P0002',
            message = 'Товар не найден';
    end if;

    if v_product.version <> p_expected_version then
        raise exception using
            errcode = '40001',
            message = 'Конфликт версий: товар изменён другим сотрудником';
    end if;

    if v_product.is_deleted = false then
        return v_product;
    end if;

    if v_product.barcode is not null
       and exists (
           select 1
           from public.products p
           where p.is_deleted = false
             and p.id <> v_product.id
             and lower(p.barcode) = lower(v_product.barcode)
       )
    then
        raise exception using
            errcode = '23505',
            message = 'Штрихкод уже используется активным товаром';
    end if;

    begin
        update public.products
        set is_deleted = false
        where id = p_id
        returning * into v_product;
    exception
        when unique_violation then
            raise exception using
                errcode = '23505',
                message = 'Штрихкод уже используется активным товаром';
    end;

    return v_product;
end;
$$;

comment on function public.restore_product(uuid, integer, text) is
    'Восстановление логически удалённого товара. Доступно только активному администратору.';
