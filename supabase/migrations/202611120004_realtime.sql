-- StorePrice, этап 5: включение Postgres Changes для таблицы products.
-- Применять после 202611100001_schema.sql и 202611100002_rls_and_rpc.sql.
-- Миграция идемпотентна: повторный запуск не дублирует публикацию.

-- Supabase Realtime отправляет строки только из publication supabase_realtime.
-- В hosted-проекте эта публикация обычно уже существует; создаём её для
-- локальной/пустой базы, чтобы миграция была самодостаточной.
do $$
begin
    if not exists (
        select 1
        from pg_publication
        where pubname = 'supabase_realtime'
    ) then
        create publication supabase_realtime;
    end if;

    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'products'
    ) then
        alter publication supabase_realtime add table public.products;
    end if;
end
$$;

-- Клиент использует событие только как сигнал к инкрементальной синхронизации
-- через PostgREST, поэтому REPLICA IDENTITY FULL не требуется.
notify pgrst, 'reload schema';
