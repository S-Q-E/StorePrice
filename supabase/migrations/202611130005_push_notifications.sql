-- StorePrice, этап 6: transactional outbox для push-уведомлений об изменении цены.
-- Применять после 001/002/003. Миграция идемпотентна.
--
-- Событие создаётся в той же транзакции, что и price_history, поэтому потеря
-- изменения цены между записью истории и постановкой уведомления невозможна.
-- Отправку выполняет Edge Function send-price-notifications через service_role.

-- ---------------------------------------------------------------------------
-- 1. Очередь событий
-- ---------------------------------------------------------------------------

create table if not exists public.notification_events (
    id uuid primary key default gen_random_uuid(),
    price_history_id uuid not null unique
        references public.price_history (id) on delete restrict,
    product_id uuid not null references public.products (id) on delete restrict,
    product_name text not null,
    old_price_kzt bigint not null,
    new_price_kzt bigint not null,
    changed_by uuid not null references public.profiles (id) on delete restrict,
    changed_by_name text not null,
    changed_at timestamptz not null,
    device_id text,
    status text not null default 'pending',
    attempts integer not null default 0,
    next_attempt_at timestamptz not null default now(),
    last_error text,
    processed_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint notification_events_name_not_blank check (btrim(product_name) <> ''),
    constraint notification_events_changed_by_name_not_blank check (btrim(changed_by_name) <> ''),
    constraint notification_events_prices_valid check (
        old_price_kzt >= 0 and new_price_kzt >= 0 and old_price_kzt <> new_price_kzt
    ),
    constraint notification_events_status_check check (
        status in ('pending', 'processing', 'sent', 'failed', 'skipped')
    ),
    constraint notification_events_attempts_non_negative check (attempts >= 0)
);

comment on table public.notification_events is
    'Transactional outbox: одно событие на каждое изменение цены. Читает только service_role.';

create index if not exists notification_events_pending_idx
    on public.notification_events (status, next_attempt_at, changed_at)
    where status in ('pending', 'processing');

alter table public.notification_events enable row level security;
revoke all on table public.notification_events from public, anon, authenticated;
grant select, update on table public.notification_events to service_role;

-- Edge Function деактивирует недействительные токены через PostgREST.
grant select, update on table public.device_tokens to service_role;

-- ---------------------------------------------------------------------------
-- 2. Доставки по устройствам
-- ---------------------------------------------------------------------------

create table if not exists public.notification_deliveries (
    id uuid primary key default gen_random_uuid(),
    event_id uuid not null
        references public.notification_events (id) on delete cascade,
    device_token_id uuid not null
        references public.device_tokens (id) on delete cascade,
    user_id uuid not null references public.profiles (id) on delete cascade,
    fcm_token text not null,
    status text not null default 'pending',
    attempts integer not null default 0,
    last_error text,
    sent_at timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint notification_deliveries_token_not_blank check (btrim(fcm_token) <> ''),
    constraint notification_deliveries_status_check check (
        status in ('pending', 'processing', 'sent', 'skipped')
    ),
    constraint notification_deliveries_attempts_non_negative check (attempts >= 0),
    constraint notification_deliveries_event_token_unique unique (event_id, device_token_id)
);

comment on table public.notification_deliveries is
    'Состояние доставки события на конкретное устройство; защищает от повторной отправки.';

-- Обновляет ограничение и при повторном применении миграции, если таблица
-- уже существовала в промежуточной версии со старым набором статусов.
alter table public.notification_deliveries
    drop constraint if exists notification_deliveries_status_check;
alter table public.notification_deliveries
    add constraint notification_deliveries_status_check check (
        status in ('pending', 'processing', 'sent', 'skipped')
    );

create index if not exists notification_deliveries_event_status_idx
    on public.notification_deliveries (event_id, status);

alter table public.notification_deliveries enable row level security;
revoke all on table public.notification_deliveries from public, anon, authenticated;
grant select, update on table public.notification_deliveries to service_role;

-- ---------------------------------------------------------------------------
-- 3. Постановка события из истории цен
-- ---------------------------------------------------------------------------

create or replace function public.enqueue_price_notification()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_changed_by_name text;
begin
    select p.display_name
    into v_changed_by_name
    from public.profiles p
    where p.id = new.changed_by;

    insert into public.notification_events (
        price_history_id,
        product_id,
        product_name,
        old_price_kzt,
        new_price_kzt,
        changed_by,
        changed_by_name,
        changed_at,
        device_id
    ) values (
        new.id,
        new.product_id,
        new.product_name_snapshot,
        new.old_price_kzt,
        new.new_price_kzt,
        new.changed_by,
        coalesce(v_changed_by_name, 'Сотрудник'),
        new.changed_at,
        new.device_id
    )
    on conflict (price_history_id) do nothing;

    return new;
end;
$$;

comment on function public.enqueue_price_notification() is
    'Ставит push-событие в outbox атомарно с вставкой price_history.';

revoke execute on function public.enqueue_price_notification()
    from public, anon, authenticated;

drop trigger if exists price_history_enqueue_notification on public.price_history;
create trigger price_history_enqueue_notification
    after insert on public.price_history
    for each row execute function public.enqueue_price_notification();

-- ---------------------------------------------------------------------------
-- 4. RPC для обработчика (только service_role)
-- ---------------------------------------------------------------------------

create or replace function public.claim_notification_events(
    p_limit integer default 20
)
returns setof public.notification_events
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_limit integer := greatest(1, least(coalesce(p_limit, 20), 100));
begin
    return query
    with candidates as (
        select e.id
        from public.notification_events e
        where (
                e.status = 'pending'
                and e.next_attempt_at <= now()
            )
            or (
                e.status = 'processing'
                and e.updated_at < now() - interval '5 minutes'
            )
        order by e.changed_at, e.id
        for update skip locked
        limit v_limit
    )
    update public.notification_events e
    set status = 'processing',
        attempts = e.attempts + 1,
        updated_at = now()
    from candidates c
    where e.id = c.id
    returning e.*;
end;
$$;

comment on function public.claim_notification_events(integer) is
    'Забирает пачку событий в обработку; повторно забирает зависшие processing старше 5 минут.';

create or replace function public.prepare_notification_deliveries(
    p_event_id uuid
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_count integer;
begin
    insert into public.notification_deliveries (
        event_id,
        device_token_id,
        user_id,
        fcm_token
    )
    select
        e.id,
        dt.id,
        dt.user_id,
        dt.fcm_token
    from public.notification_events e
    join public.device_tokens dt
        on dt.is_active
    join public.profiles p
        on p.id = dt.user_id
       and p.is_active
    where e.id = p_event_id
      and not (
          dt.user_id = e.changed_by
          and (
              e.device_id is null
              or dt.device_id = e.device_id
          )
      )
    on conflict (event_id, device_token_id) do update
    set fcm_token = excluded.fcm_token,
        updated_at = now()
    where public.notification_deliveries.status = 'pending';

    get diagnostics v_count = row_count;
    return v_count;
end;
$$;

comment on function public.prepare_notification_deliveries(uuid) is
    'Создаёт доставки для активных устройств, кроме устройства автора изменения.';

create or replace function public.claim_notification_deliveries(
    p_event_id uuid,
    p_limit integer default 500
)
returns setof public.notification_deliveries
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_limit integer := greatest(1, least(coalesce(p_limit, 500), 500));
begin
    return query
    with candidates as (
        select d.id
        from public.notification_deliveries d
        where d.event_id = p_event_id
          and d.attempts < 5
          and (
              d.status = 'pending'
              or (
                  d.status = 'processing'
                  and d.updated_at < now() - interval '5 minutes'
              )
          )
        order by d.created_at, d.id
        for update skip locked
        limit v_limit
    )
    update public.notification_deliveries d
    set status = 'processing',
        attempts = d.attempts + 1,
        updated_at = now()
    from candidates c
    where d.id = c.id
    returning d.*;
end;
$$;

comment on function public.claim_notification_deliveries(uuid, integer) is
    'Забирает доставки события в processing; sent/skipped и параллельная отправка исключены, зависшие processing повторяются через 5 минут.';

create or replace function public.finish_notification_delivery(
    p_delivery_id uuid,
    p_success boolean,
    p_permanent boolean,
    p_error text default null
)
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
    if p_success then
        update public.notification_deliveries
        set status = 'sent',
            sent_at = now(),
            last_error = null,
            updated_at = now()
        where id = p_delivery_id;
    elsif p_permanent then
        update public.notification_deliveries
        set status = 'skipped',
            last_error = left(coalesce(p_error, ''), 2000),
            updated_at = now()
        where id = p_delivery_id;
    else
        update public.notification_deliveries
        set status = 'pending',
            last_error = left(coalesce(p_error, ''), 2000),
            updated_at = now()
        where id = p_delivery_id;
    end if;
end;
$$;

comment on function public.finish_notification_delivery(uuid, boolean, boolean, text) is
    'Фиксирует результат доставки: sent, skipped (недействительный токен) или retry.';

create or replace function public.finish_notification_event(
    p_event_id uuid
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
    v_pending integer;
    v_sent integer;
    v_max_attempts integer;
    v_last_error text;
    v_status text;
    v_next_attempt_at timestamptz;
begin
    select
        count(*) filter (where status in ('pending', 'processing')),
        count(*) filter (where status = 'sent'),
        coalesce(max(attempts) filter (where status in ('pending', 'processing')), 0),
        (array_agg(last_error order by updated_at desc)
            filter (where last_error is not null))[1]
    into v_pending, v_sent, v_max_attempts, v_last_error
    from public.notification_deliveries
    where event_id = p_event_id;

    if v_pending = 0 then
        v_status := case when v_sent > 0 then 'sent' else 'skipped' end;
        update public.notification_events
        set status = v_status,
            last_error = v_last_error,
            processed_at = now(),
            updated_at = now()
        where id = p_event_id;
    elsif v_max_attempts >= 5 then
        v_status := 'failed';
        update public.notification_events
        set status = 'failed',
            last_error = v_last_error,
            processed_at = now(),
            updated_at = now()
        where id = p_event_id;
    else
        v_status := 'pending';
        v_next_attempt_at := now() + make_interval(
            secs => least(600, 30 * (1 << greatest(v_max_attempts - 1, 0)))
        );
        update public.notification_events
        set status = 'pending',
            last_error = v_last_error,
            next_attempt_at = v_next_attempt_at,
            processed_at = null,
            updated_at = now()
        where id = p_event_id;
    end if;

    return v_status;
end;
$$;

comment on function public.finish_notification_event(uuid) is
    'Завершает событие или назначает повтор; дубликаты уже отправленных доставок не создаются.';

revoke all on function public.claim_notification_events(integer)
    from public, anon, authenticated;
revoke all on function public.prepare_notification_deliveries(uuid)
    from public, anon, authenticated;
revoke all on function public.claim_notification_deliveries(uuid, integer)
    from public, anon, authenticated;
revoke all on function public.finish_notification_delivery(uuid, boolean, boolean, text)
    from public, anon, authenticated;
revoke all on function public.finish_notification_event(uuid)
    from public, anon, authenticated;

grant execute on function public.claim_notification_events(integer) to service_role;
grant execute on function public.prepare_notification_deliveries(uuid) to service_role;
grant execute on function public.claim_notification_deliveries(uuid, integer) to service_role;
grant execute on function public.finish_notification_delivery(uuid, boolean, boolean, text) to service_role;
grant execute on function public.finish_notification_event(uuid) to service_role;

-- ---------------------------------------------------------------------------
-- 5. Realtime-схема не меняется; сбросить кэш PostgREST для новых RPC.
-- ---------------------------------------------------------------------------

notify pgrst, 'reload schema';
