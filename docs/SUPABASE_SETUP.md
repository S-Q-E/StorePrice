# Настройка Supabase для StorePrice

Документ описывает этап 4: создание проекта, применение SQL-миграций, настройку входа по номеру телефона и паролю, первого администратора и проверку RLS.

**Текущий статус (2026-11-11):** живой проект `zhqvrzowbvkeurikjatf` настроен. Миграции `001` и `002` применены, RLS/RPC/триггеры созданы, Phone provider включён, публичная регистрация отключена, первый администратор создан с подтверждённым телефоном и активным профилем. Вход по телефону/паролю и RLS с реальным JWT проверены API-запросами. Миграция `003_hardening.sql` подготовлена и проверена локально, но к живому проекту **ещё не применена**; её нужно применить отдельным шагом. Android-клиент входа реализован; успешный вход в Android на устройстве и офлайн-режим ещё не проверены. Инструкции ниже остаются для нового проекта или повторной настройки.

## 1. Создание проекта

1. Создайте проект в [Supabase Dashboard](https://supabase.com/dashboard).
2. Запишите:
   - Project URL: `https://<project-ref>.supabase.co`;
   - публичный anon/publishable key;
   - project ref.
3. Публичный ключ можно использовать в Android-приложении. **Service Role key в APK, Git и документацию не попадает.**

## 2. Настройка Auth

### Телефон и пароль

1. Откройте **Authentication → Providers → Phone** и включите провайдер. В живом проекте он уже включён; при новой настройке до включения запрос `POST /auth/v1/token?grant_type=password` с телефоном возвращал `phone_provider_disabled`.
2. Отключите публичную самостоятельную регистрацию:
   - **Authentication → Settings → Allow new users to sign up** — в живом проекте выключено;
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

В живом проекте миграции `001` и `002` применены 2026-11-11. `public.profiles`, `products`, `price_history`, `device_tokens`, `audit_log` созданы, RLS включён. Для нового проекта файлы лежат в `supabase/migrations/` и применяются по порядку:

1. `202611100001_schema.sql` — таблицы, ограничения, индексы, вспомогательные функции и триггеры.
2. `202611100002_rls_and_rpc.sql` — RLS, права, серверные RPC для товаров и сотрудников.
3. `202611100003_hardening.sql` — оптимизация RLS через scalar subquery, отзыв прав у триггерных функций, RPC для FCM-токенов и единое сообщение о занятом штрихкоде.

`003` идемпотентна, её можно запускать повторно. В живом проекте она пока не применена: это отдельный шаг после просмотра diff.

### Вариант A. SQL Editor

1. Откройте **SQL Editor** в Supabase Dashboard.
2. Выполните содержимое `202611100001_schema.sql`.
3. Выполните содержимое `202611100002_rls_and_rpc.sql`.
4. Выполните содержимое `202611100003_hardening.sql`.
5. Убедитесь, что ошибок нет. `001` и `002` рассчитаны на однократное применение; `003` идемпотентна и допускает повторный запуск.

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

После `003` дополнительно проверьте, что политики используют scalar subquery, а триггерные функции закрыты:

```sql
select policyname, qual, with_check
from pg_policies
where schemaname = 'public'
  and policyname in (
    'profiles_select_self_or_active',
    'profiles_update_own_display_name',
    'products_select_active',
    'price_history_select_active',
    'device_tokens_select_own',
    'device_tokens_insert_own',
    'device_tokens_update_own',
    'device_tokens_delete_own',
    'audit_log_select_admin'
  );

select has_function_privilege(
    'authenticated',
    'public.handle_new_user()',
    'execute'
); -- false

select has_function_privilege(
    'authenticated',
    'public.register_device_token(text, text)',
    'execute'
); -- true
```

## 4. Первый администратор

Первый администратор создан 2026-11-11 через Auth Admin API: телефон подтверждён, профиль переведён в `role = admin`, `is_active = true`. Клиент не может назначить себе роль.

Для нового проекта:

1. Создайте пользователя по телефону и паролю, как описано выше.
2. Возьмите `id` пользователя из ответа Admin API или Dashboard и выполните:

```sql
update public.profiles
set role = 'admin',
    is_active = true
where id = '<uuid-пользователя>';

select p.id, u.phone, p.display_name, p.role, p.is_active
from public.profiles p
join auth.users u on u.id = p.id
where p.id = '<uuid-пользователя>';
```

Важно: Supabase Auth хранит телефон без ведущего `+` (например, `77001234567`), хотя клиент входит с `+77001234567`. Поэтому обновлять профиль по `id` надёжнее, чем по строке телефона.

3. Войдите в приложение этим телефоном и паролем. Если профиль не активирован, RLS вернёт отказ.

### Активация сотрудника

```sql
update public.profiles p
set is_active = true
from auth.users u
where p.id = u.id
  and u.phone = '77007654321';
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
- FCM-токены регистрируются через `register_device_token`, чтобы при смене пользователя на общем телефоне оставалась одна активная запись; `unregister_device_token` отключает устройство;
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

### Результат проверки живого проекта (2026-11-11)

- созданы 5 таблиц, 9 политик RLS, 8 функций и 5 триггеров;
- вход администратора по телефону/паролю через публичный publishable key → `200`, access token получен;
- `GET /rest/v1/products` и `GET /rest/v1/profiles` с реальным JWT → `200`;
- `GET /rest/v1/profiles` без JWT → `401`;
- `GET /auth/v1/settings` → `external.phone: true`, `disable_signup: true`.

Ещё не проверены через живой JWT: RPC создания/изменения/удаления товара, деактивация сотрудника, смена роли, неизменяемость истории цен и refresh токена.

## 7. Конфигурация Android

Публичные параметры читаются из локального файла, исключённого из Git. Ожидаемые ключи:

```properties
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_ANON_KEY=<publishable-key>
```

`SUPABASE_URL` должен быть базовым адресом проекта, без `/rest/v1/`. Сборка дополнительно убирает завершающий `/` и `/rest/v1`, но правильнее хранить базовый URL. Параметры попадают в `BuildConfig` только как публичные значения.

Не добавляйте в `local.properties`, `BuildConfig`, APK или Git:

- `SUPABASE_SERVICE_ROLE_KEY` / secret key;
- Personal Access Token Supabase;
- пароли сотрудников;
- Firebase service account;
- приватные ключи.

Для одноразовой настройки живого проекта используется локальный `supabase_admin.env` с PAT, secret key и данными администратора. Он добавлен в `.gitignore` и не должен попадать в репозиторий. После настройки его лучше перенести в менеджер секретов или удалить.

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

- [x] Публичная регистрация отключена (`disable_signup: true`).
- [x] Первый администратор создан, телефон подтверждён, профиль активен.
- [x] Все пять таблиц имеют RLS.
- [x] `anon` не имеет прав на таблицы (анонимное чтение `profiles` → `401`).
- [ ] Новый пользователь получает `is_active = false` (триггер применён, live-сценарий ещё не проверен).
- [ ] Проверено, что деактивированный сотрудник не читает каталог и не вызывает RPC.
- [ ] Проверено, что сотрудник не может изменить себе роль.
- [ ] Проверено, что история цен не редактируется и не удаляется.
- [x] Проверено, что `service_role`/secret key и PAT отсутствуют в APK, Git и `local.properties`; они лежат только в игнорируемом `supabase_admin.env`.
- [ ] Проверено, что прямой INSERT/UPDATE товара из клиента запрещён, запись идёт через RPC.

## 9. Ограничения текущего этапа

- Живой проект Supabase настроен: миграции `001`/`002` применены, `003` подготовлена; Phone provider включён, публичная регистрация отключена, первый администратор создан.
- Вход администратора и чтение `products`/`profiles` через RLS проверены API-запросами; анонимный доступ к `profiles` получает `401`.
- Android-авторизация реализована; успешный вход в Android на устройстве, деактивация сотрудника, refresh и офлайн-режим на живом проекте ещё не проверены.
- Тестовый сотрудник ещё не создан; RPC записи через живой JWT не проверены.
- Каталог всё ещё работает только с Room; синхронизация с Supabase не начата.
- Realtime, FCM и административный UI — следующие этапы.
- Номер телефона может быть переиспользован оператором связи. Поэтому сотрудников деактивируют, а не удаляют; аккаунты создаёт только администратор.
