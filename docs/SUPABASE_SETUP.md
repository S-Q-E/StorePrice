# Настройка Supabase для StorePrice

Документ описывает этап 4: создание проекта, применение SQL-миграций, настройку входа по номеру телефона и паролю, первого администратора и проверку RLS.

**Текущий статус (2026-11-10):** живой проект Supabase создан, Auth отвечает. Миграции подготовлены и проверены на локальном PostgreSQL 17 с заглушками Supabase Auth, но к живому проекту **не применены**. В Auth сейчас **отключён вход по телефону** (`phone_provider_disabled`), а публичная регистрация **включена**. Android-клиент входа уже реализован и собирается, но сквозной вход не проверен, пока не выполнены шаги 2–4.

## 1. Создание проекта

1. Создайте проект в [Supabase Dashboard](https://supabase.com/dashboard).
2. Запишите:
   - Project URL: `https://<project-ref>.supabase.co`;
   - публичный anon/publishable key;
   - project ref.
3. Публичный ключ можно использовать в Android-приложении. **Service Role key в APK, Git и документацию не попадает.**

## 2. Настройка Auth

### Телефон и пароль

1. Откройте **Authentication → Providers → Phone** и включите провайдер. Сейчас в проекте он выключен: запрос `POST /auth/v1/token?grant_type=password` с телефоном возвращает `phone_provider_disabled`.
2. Отключите публичную самостоятельную регистрацию:
   - **Authentication → Settings → Allow new users to sign up** — выключено;
   - дополнительные переключатели регистрации по телефону — выключены.
3. Вход выполняется по номеру телефона и паролю. SMS-провайдер нужен для OTP/подтверждения номера через SMS. Если администратор создаёт пользователя с уже подтверждённым телефоном, SMS для входа не требуется; при запросе Dashboard можно оставить подтверждение по SMS выключенным.
4. Настройте срок жизни access token и refresh token в **Authentication → Sessions**. Клиент обязан обрабатывать истечение сессии и выполнять refresh.

После включения проверьте, что ложный вход возвращает уже не `phone_provider_disabled`, а `invalid_credentials`:

```bash
curl -sS -X POST "$SUPABASE_URL/auth/v1/token?grant_type=password" \
  -H "apikey: $SUPABASE_ANON_KEY" \
  -H "Content-Type: application/json" \
  -d '{"phone":"+77000000000","password":"not-a-real-password"}'
```

Публичная регистрация отключена, потому что новый профиль создаётся неактивным, а доступ к каталогу даёт только активированный профиль.

### Создание сотрудника

В Dashboard: **Authentication → Users → Add user**:

- phone: `+7...`;
- password: надёжный пароль;
- auto confirm user: включено;
- user metadata: `{"display_name":"Имя сотрудника"}`.

Триггер `handle_new_user` создаст запись в `public.profiles` с ролью `employee` и `is_active = false`. Сотрудник сможет войти в Auth, но RLS не даст ему доступ к каталогу, пока администратор не активирует профиль.

Альтернатива для серверного скрипта — Supabase Admin API. Ключ `service_role` используется только на доверенном сервере и никогда не попадает в Android-модуль:

```bash
curl -X POST "$SUPABASE_URL/auth/v1/admin/users" \
  -H "apikey: $SUPABASE_SERVICE_ROLE_KEY" \
  -H "Authorization: Bearer $SUPABASE_SERVICE_ROLE_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "phone": "+77001234567",
    "password": "replace-with-strong-password",
    "phone_confirm": true,
    "user_metadata": {"display_name": "Имя сотрудника"}
  }'
```

## 3. Применение миграций

В текущем живом проекте таблица `public.profiles` ещё не найдена (`PGRST205`), поэтому миграции действительно не применены.

Файлы лежат в `supabase/migrations/` и применяются по порядку:

1. `202611100001_schema.sql` — таблицы, ограничения, индексы, вспомогательные функции и триггеры.
2. `202611100002_rls_and_rpc.sql` — RLS, права, серверные RPC для товаров и сотрудников.

### Вариант A. SQL Editor

1. Откройте **SQL Editor** в Supabase Dashboard.
2. Выполните содержимое `202611100001_schema.sql`.
3. Выполните содержимое `202611100002_rls_and_rpc.sql`.
4. Убедитесь, что ошибок нет. Повторно те же файлы не запускайте: миграции рассчитаны на однократное применение.

### Вариант B. Supabase CLI

```bash
npm install -g supabase
supabase login
supabase link --project-ref <project-ref>
supabase db push
```

CLI применит файлы в лексикографическом порядке имён.

### Проверка применения

```sql
select tablename, rowsecurity
from pg_tables
where schemaname = 'public'
order by tablename;

select tablename, policyname, cmd
from pg_policies
where schemaname = 'public'
order by tablename, policyname;

select indexname, indexdef
from pg_indexes
where schemaname = 'public'
  and tablename = 'products';
```

Ожидаемые таблицы: `profiles`, `products`, `price_history`, `device_tokens`, `audit_log`. У всех должна быть включена RLS (`rowsecurity = true`).

## 4. Первый администратор

Первый администратор создаётся вручную: клиент не может назначить себе роль.

1. Создайте пользователя по телефону и паролю, как описано выше.
2. Выполните в SQL Editor:

```sql
update public.profiles p
set role = 'admin',
    is_active = true
from auth.users u
where p.id = u.id
  and u.phone = '+77001234567';

select p.id, u.phone, p.display_name, p.role, p.is_active
from public.profiles p
join auth.users u on u.id = p.id
where u.phone = '+77001234567';
```

3. Войдите в приложение этим телефоном и паролем. Если профиль не активирован, RLS вернёт отказ.

### Активация сотрудника

```sql
update public.profiles p
set is_active = true
from auth.users u
where p.id = u.id
  and u.phone = '+77007654321';
```

После появления административного экрана те же действия будут доступны через RPC `admin_set_employee_active` и `admin_set_employee_role`. Обе функции проверяют активного администратора на сервере и запрещают менять собственную роль/деактивировать себя.

## 5. Как устроена защита

| Объект | Чтение | Запись |
|---|---|---|
| `profiles` | активный сотрудник видит профили; неактивный — только свой | своё `display_name`; роль и активность — только через административные RPC |
| `products` | активный сотрудник, включая логически удалённые для будущей синхронизации | только RPC `create_product`, `update_product`, `soft_delete_product`, `restore_product` |
| `price_history` | активный сотрудник | только серверный триггер; строки неизменяемы |
| `device_tokens` | только свои записи | только свои записи |
| `audit_log` | только администратор | только серверные функции/триггеры |

Дополнительно:

- анонимная роль не имеет прав на таблицы;
- уникальность штрихкода — частичный уникальный индекс по `lower(barcode)` среди неудалённых товаров;
- `version` увеличивается триггером, а RPC проверяет ожидаемую версию и возвращает конфликт `40001`;
- история цены пишется атомарно в том же `UPDATE`, отдельного клиентского INSERT нет;
- создание, изменение, удаление и восстановление товара пишутся в `audit_log`;
- пароли хранятся и проверяются Supabase Auth, не приложением.

## 6. Проверка RLS вручную

В SQL Editor можно временно переключить роль и claims. Все изменения откатываются.

```sql
begin;
set local role authenticated;
set local request.jwt.claim.sub = '<uuid-сотрудника>';
set local request.jwt.claim.role = 'authenticated';
set local request.jwt.claims = '{"sub":"<uuid-сотрудника>","role":"authenticated"}';

select count(*) from public.products;
select count(*) from public.audit_log;  -- для employee должно быть 0
rollback;
```

Для анонимного доступа:

```sql
begin;
set local role anon;
select count(*) from public.products;  -- ожидается отказ в доступе
rollback;
```

Проверка записи через RPC:

```sql
begin;
set local role authenticated;
set local request.jwt.claim.sub = '<uuid-администратора>';
set local request.jwt.claim.role = 'authenticated';

select * from public.create_product('Тестовый товар', 100, '1234567890123', 'doc-test');
select * from public.audit_log order by created_at desc limit 1;
rollback;
```

`create_product` должен вернуть строку, а `audit_log` — запись с действием `create`. После `rollback` тестовые данные исчезнут.

## 7. Конфигурация Android

Публичные параметры читаются из локального файла, исключённого из Git. Ожидаемые ключи:

```properties
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_ANON_KEY=<publishable-key>
```

`SUPABASE_URL` должен быть базовым адресом проекта, без `/rest/v1/`. Сборка дополнительно убирает завершающий `/` и `/rest/v1`, но правильнее хранить базовый URL. Параметры попадают в `BuildConfig` только как публичные значения.

Не добавляйте в `local.properties` или `BuildConfig`:

- `SUPABASE_SERVICE_ROLE_KEY`;
- пароли сотрудников;
- Firebase service account;
- приватные ключи.

Реализовано в Android:

- `supabase-kt` 3.0.0, `auth-kt`, `postgrest-kt`, Ktor OkHttp;
- экран входа по номеру телефона и паролю с валидацией и русскими сообщениями;
- нормализация телефона к E.164 (`8 700...`, `+7 700...`, 10 цифр);
- восстановление сессии, refresh, выход;
- проверка `public.profiles.is_active` после входа и при обновлении сессии;
- неактивный или отсутствующий профиль разлогинивает пользователя;
- access/refresh token хранятся в приватных `SharedPreferences`; пароль не сохраняется;
- `android:allowBackup="false"` и запрет cloud backup/device transfer, чтобы токены не копировались;
- выход доступен из меню аккаунта в каталоге.

Сессия хранится в обычных приватных `SharedPreferences`, без отдельного шифрования. Это не секрет уровня `service_role`, но для повышенных требований можно заменить `SessionManager` на EncryptedSharedPreferences/Keystore.

Каталог пока читает и пишет только Room. Подключение к общей базе через RPC — следующий шаг после живой проверки Auth.

## 8. Чек-лист безопасности перед подключением клиента

- [ ] Публичная регистрация отключена.
- [ ] Первый администратор создан вручную.
- [ ] Все пять таблиц имеют RLS.
- [ ] `anon` не имеет прав на таблицы.
- [ ] Новый пользователь получает `is_active = false`.
- [ ] Проверено, что деактивированный сотрудник не читает каталог и не вызывает RPC.
- [ ] Проверено, что сотрудник не может изменить себе роль.
- [ ] Проверено, что история цен не редактируется и не удаляется.
- [ ] Проверено, что `service_role` отсутствует в APK, Git и `local.properties`.
- [ ] Проверено, что прямой INSERT/UPDATE товара из клиента запрещён, запись идёт через RPC.

## 9. Ограничения текущего этапа

- Живой проект Supabase создан, URL и публичный ключ настроены локально; `service_role` не запрашивался и не сохранялся.
- Миграции к живому проекту не применены; `public.profiles` отсутствует.
- Вход по телефону в Auth выключен, публичная регистрация включена — это нужно исправить в Dashboard.
- Первый администратор и тестовый сотрудник ещё не созданы.
- Android-авторизация реализована и проверена локально (unit-тесты, запуск экрана на эмуляторе, обработка `phone_provider_disabled`), но успешный вход, RLS с реальным JWT и деактивация профиля на живом проекте не проверены.
- Каталог всё ещё работает только с Room; синхронизация с Supabase не начата.
- Realtime, FCM и административный UI — следующие этапы.
- Номер телефона может быть переиспользован оператором связи. Поэтому сотрудников деактивируют, а не удаляют; аккаунты создаёт только администратор.
