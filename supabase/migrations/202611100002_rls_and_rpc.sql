-- StorePrice, этап 4: RLS, права и серверные операции.
-- Применять после 202611100001_schema.sql.

-- ---------------------------------------------------------------------------
-- 1. Row Level Security
-- ---------------------------------------------------------------------------

alter table public.profiles enable row level security;
alter table public.products enable row level security;
alter table public.price_history enable row level security;
alter table public.device_tokens enable row level security;
alter table public.audit_log enable row level security;

create policy profiles_select_self_or_active
    on public.profiles
    for select
    to authenticated
    using (id = auth.uid() or public.current_user_is_active());

-- Сотрудник может изменить только отображаемое имя. Роль и is_active
-- меняются администратором через серверные функции.
create policy profiles_update_own_display_name
    on public.profiles
    for update
    to authenticated
    using (id = auth.uid() and public.current_user_is_active())
    with check (id = auth.uid() and public.current_user_is_active());

create policy products_select_active
    on public.products
    for select
    to authenticated
    using (public.current_user_is_active());

create policy price_history_select_active
    on public.price_history
    for select
    to authenticated
    using (public.current_user_is_active());

create policy device_tokens_select_own
    on public.device_tokens
    for select
    to authenticated
    using (user_id = auth.uid() and public.current_user_is_active());

create policy device_tokens_insert_own
    on public.device_tokens
    for insert
    to authenticated
    with check (user_id = auth.uid() and public.current_user_is_active());

create policy device_tokens_update_own
    on public.device_tokens
    for update
    to authenticated
    using (user_id = auth.uid() and public.current_user_is_active())
    with check (user_id = auth.uid() and public.current_user_is_active());

create policy device_tokens_delete_own
    on public.device_tokens
    for delete
    to authenticated
    using (user_id = auth.uid() and public.current_user_is_active());

create policy audit_log_select_admin
    on public.audit_log
    for select
    to authenticated
    using (public.current_user_is_admin());

-- ---------------------------------------------------------------------------
-- 2. Права таблиц
-- ---------------------------------------------------------------------------

revoke all on table public.profiles from anon, authenticated;
revoke all on table public.products from anon, authenticated;
revoke all on table public.price_history from anon, authenticated;
revoke all on table public.device_tokens from anon, authenticated;
revoke all on table public.audit_log from anon, authenticated;

grant usage on schema public to authenticated;

grant select on table public.profiles to authenticated;
grant update (display_name, updated_at) on table public.profiles to authenticated;

grant select on table public.products to authenticated;

grant select on table public.price_history to authenticated;

grant select, insert, update, delete on table public.device_tokens to authenticated;

grant select on table public.audit_log to authenticated;

-- ---------------------------------------------------------------------------
-- 3. Серверные операции с товарами
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

    insert into public.products (name, price_kzt, barcode, created_by, updated_by)
    values (
        btrim(p_name),
        p_price_kzt,
        nullif(btrim(p_barcode), ''),
        v_uid,
        v_uid
    )
    returning * into v_product;

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

    update public.products
    set name = btrim(p_name),
        price_kzt = p_price_kzt,
        barcode = nullif(btrim(p_barcode), '')
    where id = p_id
    returning * into v_product;

    return v_product;
end;
$$;

comment on function public.update_product(uuid, integer, text, bigint, text, text) is
    'Обновляет товар с проверкой version. История цены и аудит пишутся триггерами.';

create or replace function public.soft_delete_product(
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
            message = 'Только администратор может удалять товар';
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

    if v_product.is_deleted then
        return v_product;
    end if;

    update public.products
    set is_deleted = true
    where id = p_id
    returning * into v_product;

    return v_product;
end;
$$;

comment on function public.soft_delete_product(uuid, integer, text) is
    'Логическое удаление товара. Доступно только активному администратору.';

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

    update public.products
    set is_deleted = false
    where id = p_id
    returning * into v_product;

    return v_product;
end;
$$;

comment on function public.restore_product(uuid, integer, text) is
    'Восстановление логически удалённого товара. Доступно только активному администратору.';

-- ---------------------------------------------------------------------------
-- 4. Административные операции с сотрудниками
-- ---------------------------------------------------------------------------

create or replace function public.admin_set_employee_active(
    p_user_id uuid,
    p_is_active boolean,
    p_device_id text default null
)
returns public.profiles
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_profile public.profiles;
begin
    if not public.current_user_is_admin() then
        raise exception using
            errcode = '42501',
            message = 'Только администратор может менять активность сотрудника';
    end if;

    if p_user_id is null or p_is_active is null then
        raise exception using
            errcode = '22023',
            message = 'Не переданы сотрудник или признак активности';
    end if;

    if p_user_id = auth.uid() and p_is_active = false then
        raise exception using
            errcode = '22023',
            message = 'Нельзя деактивировать собственную учётную запись';
    end if;

    perform set_config('storeprice.device_id', coalesce(p_device_id, ''), true);

    update public.profiles
    set is_active = p_is_active
    where id = p_user_id
    returning * into v_profile;

    if not found then
        raise exception using
            errcode = 'P0002',
            message = 'Сотрудник не найден';
    end if;

    insert into public.audit_log (product_id, user_id, action, device_id, details)
    values (
        null,
        auth.uid(),
        'profile_update',
        public.current_device_id(),
        jsonb_build_object('target_user_id', p_user_id, 'is_active', p_is_active)
    );

    return v_profile;
end;
$$;

comment on function public.admin_set_employee_active(uuid, boolean, text) is
    'Активирует или деактивирует сотрудника. Только для активного администратора.';

create or replace function public.admin_set_employee_role(
    p_user_id uuid,
    p_role text,
    p_device_id text default null
)
returns public.profiles
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_profile public.profiles;
begin
    if not public.current_user_is_admin() then
        raise exception using
            errcode = '42501',
            message = 'Только администратор может менять роль сотрудника';
    end if;

    if p_user_id is null or p_role is null or p_role not in ('admin', 'employee') then
        raise exception using
            errcode = '22023',
            message = 'Некорректный сотрудник или роль';
    end if;

    if p_user_id = auth.uid() then
        raise exception using
            errcode = '22023',
            message = 'Нельзя менять собственную роль';
    end if;

    perform set_config('storeprice.device_id', coalesce(p_device_id, ''), true);

    update public.profiles
    set role = p_role
    where id = p_user_id
    returning * into v_profile;

    if not found then
        raise exception using
            errcode = 'P0002',
            message = 'Сотрудник не найден';
    end if;

    insert into public.audit_log (product_id, user_id, action, device_id, details)
    values (
        null,
        auth.uid(),
        'profile_update',
        public.current_device_id(),
        jsonb_build_object('target_user_id', p_user_id, 'role', p_role)
    );

    return v_profile;
end;
$$;

comment on function public.admin_set_employee_role(uuid, text, text) is
    'Меняет роль сотрудника на admin или employee. Только для активного администратора.';

-- ---------------------------------------------------------------------------
-- 5. Права на функции
-- ---------------------------------------------------------------------------

revoke all on function public.current_user_is_active() from public, anon;
grant execute on function public.current_user_is_active() to authenticated;

revoke all on function public.current_user_is_admin() from public, anon;
grant execute on function public.current_user_is_admin() to authenticated;

revoke all on function public.current_device_id() from public, anon, authenticated;

revoke all on function public.create_product(text, bigint, text, text) from public, anon;
grant execute on function public.create_product(text, bigint, text, text) to authenticated;

revoke all on function public.update_product(uuid, integer, text, bigint, text, text)
    from public, anon;
grant execute on function public.update_product(uuid, integer, text, bigint, text, text)
    to authenticated;

revoke all on function public.soft_delete_product(uuid, integer, text) from public, anon;
grant execute on function public.soft_delete_product(uuid, integer, text) to authenticated;

revoke all on function public.restore_product(uuid, integer, text) from public, anon;
grant execute on function public.restore_product(uuid, integer, text) to authenticated;

revoke all on function public.admin_set_employee_active(uuid, boolean, text)
    from public, anon;
grant execute on function public.admin_set_employee_active(uuid, boolean, text)
    to authenticated;

revoke all on function public.admin_set_employee_role(uuid, text, text)
    from public, anon;
grant execute on function public.admin_set_employee_role(uuid, text, text)
    to authenticated;
