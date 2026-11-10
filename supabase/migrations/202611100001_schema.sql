-- StorePrice, этап 4: базовая схема Supabase.
-- Применять после создания проекта Supabase: SQL Editor или `supabase db push`.
-- Все даты хранятся как timestamptz (UTC).

-- ---------------------------------------------------------------------------
-- 1. Сотрудники
-- ---------------------------------------------------------------------------

create table public.profiles (
    id uuid primary key references auth.users (id) on delete cascade,
    display_name text not null,
    role text not null default 'employee',
    is_active boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint profiles_display_name_not_blank check (btrim(display_name) <> ''),
    constraint profiles_role_check check (role in ('admin', 'employee'))
);

comment on table public.profiles is
    'Профили сотрудников. Новый пользователь создаётся неактивным; администратор активирует его.';
comment on column public.profiles.role is
    'admin или employee. Роль меняется только администратором через серверную функцию.';

-- ---------------------------------------------------------------------------
-- 2. Товары
-- ---------------------------------------------------------------------------

create table public.products (
    id uuid primary key default gen_random_uuid(),
    barcode text,
    name text not null,
    price_kzt bigint not null,
    is_deleted boolean not null default false,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    created_by uuid references public.profiles (id) on delete restrict,
    updated_by uuid references public.profiles (id) on delete restrict,
    version integer not null default 1,
    constraint products_name_not_blank check (btrim(name) <> ''),
    constraint products_price_non_negative check (price_kzt >= 0),
    constraint products_version_positive check (version > 0),
    constraint products_barcode_not_blank check (barcode is null or btrim(barcode) <> ''),
    constraint products_barcode_length check (barcode is null or char_length(barcode) <= 64)
);

comment on table public.products is
    'Общий каталог. Удаление логическое, чтобы штрихкод можно было переиспользовать.';
comment on column public.products.barcode is
    'Текст, чтобы сохранять ведущие нули. Пустая строка нормализуется в NULL.';
comment on column public.products.price_kzt is
    'Цена в целых тенге. Дробные значения не используются.';
comment on column public.products.version is
    'Версия для контроля конкурентных изменений. Увеличивается серверным триггером.';

-- Уникальность только среди активных товаров; регистр не важен.
create unique index products_active_barcode_uidx
    on public.products (lower(barcode))
    where barcode is not null and is_deleted = false;

create index products_updated_at_idx
    on public.products (updated_at desc);

create index products_active_name_idx
    on public.products (lower(name))
    where is_deleted = false;

-- ---------------------------------------------------------------------------
-- 3. История цен
-- ---------------------------------------------------------------------------

create table public.price_history (
    id uuid primary key default gen_random_uuid(),
    product_id uuid not null references public.products (id) on delete restrict,
    product_name_snapshot text not null,
    old_price_kzt bigint not null,
    new_price_kzt bigint not null,
    changed_by uuid not null references public.profiles (id) on delete restrict,
    changed_at timestamptz not null default now(),
    operation_id uuid not null default gen_random_uuid(),
    device_id text,
    constraint price_history_name_not_blank check (btrim(product_name_snapshot) <> ''),
    constraint price_history_old_non_negative check (old_price_kzt >= 0),
    constraint price_history_new_non_negative check (new_price_kzt >= 0),
    constraint price_history_price_changed check (old_price_kzt <> new_price_kzt)
);

comment on table public.price_history is
    'Неизменяемая история изменений цены. Записывается серверным триггером атомарно с UPDATE products.';

create index price_history_product_changed_at_idx
    on public.price_history (product_id, changed_at desc);

create index price_history_changed_at_idx
    on public.price_history (changed_at desc);

create index price_history_changed_by_idx
    on public.price_history (changed_by);

-- ---------------------------------------------------------------------------
-- 4. Токены устройств
-- ---------------------------------------------------------------------------

create table public.device_tokens (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.profiles (id) on delete cascade,
    fcm_token text not null,
    device_id text not null,
    is_active boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint device_tokens_fcm_not_blank check (btrim(fcm_token) <> ''),
    constraint device_tokens_device_not_blank check (btrim(device_id) <> '')
);

comment on table public.device_tokens is
    'FCM-токены устройств. Пользователь видит и меняет только свои записи.';

create index device_tokens_user_id_idx
    on public.device_tokens (user_id);

create unique index device_tokens_active_user_device_uidx
    on public.device_tokens (user_id, device_id)
    where is_active;

create unique index device_tokens_active_fcm_uidx
    on public.device_tokens (fcm_token)
    where is_active;

-- ---------------------------------------------------------------------------
-- 5. Аудит
-- ---------------------------------------------------------------------------

create table public.audit_log (
    id uuid primary key default gen_random_uuid(),
    product_id uuid references public.products (id) on delete set null,
    user_id uuid references public.profiles (id) on delete set null,
    action text not null,
    device_id text,
    details jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    constraint audit_log_action_check check (
        action in ('create', 'update', 'delete', 'restore', 'hard_delete', 'profile_update')
    )
);

comment on table public.audit_log is
    'Журнал создания, изменения, удаления и восстановления товаров, а также изменений сотрудников.';

create index audit_log_created_at_idx
    on public.audit_log (created_at desc);

create index audit_log_product_id_idx
    on public.audit_log (product_id, created_at desc);

create index audit_log_user_id_idx
    on public.audit_log (user_id, created_at desc);

-- ---------------------------------------------------------------------------
-- 6. Вспомогательные функции
-- ---------------------------------------------------------------------------

create or replace function public.current_user_is_active()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.profiles p
        where p.id = auth.uid()
          and p.is_active = true
    );
$$;

comment on function public.current_user_is_active() is
    'True, если текущий пользователь авторизован и его профиль активен.';

create or replace function public.current_user_is_admin()
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
    select exists (
        select 1
        from public.profiles p
        where p.id = auth.uid()
          and p.is_active = true
          and p.role = 'admin'
    );
$$;

comment on function public.current_user_is_admin() is
    'True, если текущий пользователь активен и имеет роль admin.';

create or replace function public.current_device_id()
returns text
language sql
stable
set search_path = ''
as $$
    select coalesce(
        nullif(current_setting('storeprice.device_id', true), ''),
        nullif(current_setting('request.headers', true), '')::jsonb ->> 'x-device-id'
    );
$$;

comment on function public.current_device_id() is
    'Идентификатор установки из локальной настройки транзакции или заголовка x-device-id.';

-- ---------------------------------------------------------------------------
-- 7. Триггеры
-- ---------------------------------------------------------------------------

create or replace function public.touch_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    new.updated_at := now();
    return new;
end;
$$;

create or replace function public.set_product_audit_fields()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
    v_uid uuid := auth.uid();
begin
    if tg_op = 'INSERT' then
        new.name := btrim(new.name);
        new.barcode := nullif(btrim(new.barcode), '');
        new.created_at := now();
        new.updated_at := now();
        new.created_by := coalesce(v_uid, new.created_by);
        new.updated_by := coalesce(v_uid, new.updated_by);
        new.version := 1;
        new.is_deleted := false;

        if new.updated_by is null then
            raise exception using
                errcode = '23502',
                message = 'Не удалось определить автора создания товара';
        end if;
        return new;
    end if;

    new.name := btrim(new.name);
    new.barcode := nullif(btrim(new.barcode), '');
    new.created_at := old.created_at;
    new.created_by := old.created_by;
    new.updated_at := now();
    new.updated_by := coalesce(v_uid, new.updated_by, old.updated_by);
    new.version := old.version + 1;

    -- Прямые изменения is_deleted сотрудником запрещены даже при наличии прав на UPDATE.
    if new.is_deleted is distinct from old.is_deleted
       and v_uid is not null
       and not public.current_user_is_admin() then
        raise exception using
            errcode = '42501',
            message = 'Только администратор может удалять или восстанавливать товар';
    end if;

    if new.updated_by is null then
        raise exception using
            errcode = '23502',
            message = 'Не удалось определить автора изменения товара';
    end if;

    return new;
end;
$$;

create or replace function public.record_price_history()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_changed_by uuid;
begin
    if tg_op <> 'UPDATE' or new.price_kzt is not distinct from old.price_kzt then
        return new;
    end if;

    v_changed_by := coalesce(new.updated_by, auth.uid());
    if v_changed_by is null then
        raise exception using
            errcode = '23502',
            message = 'Не удалось определить автора изменения цены';
    end if;

    insert into public.price_history (
        product_id,
        product_name_snapshot,
        old_price_kzt,
        new_price_kzt,
        changed_by,
        changed_at,
        operation_id,
        device_id
    ) values (
        new.id,
        new.name,
        old.price_kzt,
        new.price_kzt,
        v_changed_by,
        now(),
        gen_random_uuid(),
        public.current_device_id()
    );

    return new;
end;
$$;

create or replace function public.log_product_change()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_action text;
    v_actor uuid;
    v_product_id uuid;
    v_details jsonb;
begin
    if tg_op = 'INSERT' then
        v_action := 'create';
        v_actor := coalesce(new.updated_by, auth.uid());
        v_product_id := new.id;
        v_details := jsonb_build_object(
            'name', new.name,
            'price_kzt', new.price_kzt,
            'barcode', new.barcode,
            'version', new.version
        );
    elsif tg_op = 'UPDATE' then
        v_actor := coalesce(new.updated_by, auth.uid());
        v_product_id := new.id;

        if old.is_deleted = false and new.is_deleted = true then
            v_action := 'delete';
        elsif old.is_deleted = true and new.is_deleted = false then
            v_action := 'restore';
        else
            v_action := 'update';
        end if;

        v_details := jsonb_build_object(
            'old_name', old.name,
            'new_name', new.name,
            'old_price_kzt', old.price_kzt,
            'new_price_kzt', new.price_kzt,
            'old_barcode', old.barcode,
            'new_barcode', new.barcode,
            'old_version', old.version,
            'new_version', new.version,
            'is_deleted', new.is_deleted
        );
    else
        v_action := 'hard_delete';
        v_actor := old.updated_by;
        -- Товар уже удалён, поэтому FK product_id не должен ссылаться на него.
        v_product_id := null;
        v_details := jsonb_build_object(
            'product_id', old.id,
            'name', old.name,
            'price_kzt', old.price_kzt,
            'barcode', old.barcode,
            'version', old.version
        );
    end if;

    insert into public.audit_log (product_id, user_id, action, device_id, details)
    values (v_product_id, v_actor, v_action, public.current_device_id(), v_details);

    if tg_op = 'DELETE' then
        return old;
    end if;
    return new;
end;
$$;

create or replace function public.prevent_price_history_mutation()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
    -- Service Role допускается только для обслуживания; обычные роли и админы — нет.
    if coalesce(auth.role(), '') = 'service_role' then
        if tg_op = 'DELETE' then
            return old;
        end if;
        return new;
    end if;

    raise exception using
        errcode = '42501',
        message = 'История цен неизменяема';
end;
$$;

create or replace function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_display_name text;
begin
    v_display_name := coalesce(
        nullif(btrim(new.raw_user_meta_data ->> 'display_name'), ''),
        nullif(btrim(new.phone), ''),
        nullif(btrim(split_part(coalesce(new.email, ''), '@', 1)), ''),
        'Сотрудник'
    );

    insert into public.profiles (id, display_name, role, is_active)
    values (new.id, v_display_name, 'employee', false)
    on conflict (id) do nothing;

    return new;
end;
$$;

create trigger profiles_touch_updated_at
    before update on public.profiles
    for each row execute function public.touch_updated_at();

create trigger device_tokens_touch_updated_at
    before update on public.device_tokens
    for each row execute function public.touch_updated_at();

create trigger products_set_audit_fields
    before insert or update on public.products
    for each row execute function public.set_product_audit_fields();

create trigger products_record_price_history
    after update of price_kzt on public.products
    for each row execute function public.record_price_history();

create trigger products_log_change
    after insert or update or delete on public.products
    for each row execute function public.log_product_change();

create trigger price_history_prevent_mutation
    before update or delete on public.price_history
    for each row execute function public.prevent_price_history_mutation();

create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();
