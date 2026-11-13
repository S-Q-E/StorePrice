# Push-уведомления StorePrice (этап 6)

Документ описывает локально реализованный механизм push-уведомлений об изменении цены и порядок его подключения к живому проекту. На 2026-11-13 Firebase-проект и `google-services.json` ещё не созданы, миграция `202611130005_push_notifications.sql` не применена к живому Supabase, Edge Function не задеплоена. Поэтому живые push-уведомления пока не отправлялись.

## 1. Что реализовано

### Android

- Firebase Messaging подключён через `firebase-bom` и `firebase-messaging`.
- `google-services` применяется только при наличии `app/google-services.json`. Без файла APK собирается, но регистрация FCM-токена отключена (`BuildConfig.FIREBASE_CONFIGURED = false`).
- `DeviceIdentity` генерирует случайный UUID установки и хранит его в приватном `SharedPreferences`. Идентификатор не аппаратный и используется только для исключения устройства автора изменения.
- После входа, восстановления сети или обновления FCM-токена приложение регистрирует токен через RPC `register_device_token`; перед выходом вызывает `unregister_device_token`, чтобы общий телефон не получал уведомления предыдущего сотрудника.
- Создан канал `price_changes`, запрашивается разрешение `POST_NOTIFICATIONS` на Android 13+.
- `StorePriceMessagingService` разбирает data-часть FCM, проверяет обязательные поля и показывает локальное уведомление с названием товара, старой и новой ценой, именем автора и временем изменения.
- Нажатие на уведомление открывает `MainActivity`; принимаются и собственный extra `com.storeprice.app.extra.PRODUCT_ID`, и FCM-data-ключ `product_id` (его Firebase добавляет в intent системного уведомления). После входа Navigation Compose открывает карточку товара; для уже запущенного приложения используется `singleTop` и `onNewIntent`.
- Если пользователь отклонил разрешение, каталог продолжает работать; уведомления можно включить в системных настройках.

### Сервер

- Миграция `202611130005_push_notifications.sql` создаёт transactional outbox:
  - `notification_events` — одно событие на изменение цены, создаётся триггером `price_history_enqueue_notification` в той же транзакции, что и `price_history`;
  - `notification_deliveries` — отдельная строка на каждое устройство, защита от повторной отправки и журнал ошибок;
  - RPC `claim_notification_events`, `prepare_notification_deliveries`, `claim_notification_deliveries`, `finish_notification_delivery`, `finish_notification_event`.
- Событие не создаётся, если цена не изменилась. Событие создаётся только после успешной записи истории цены.
- Получатели — активные устройства активных сотрудников. Устройство автора изменения исключается; если `device_id` автора неизвестен, исключаются все его устройства.
- Доставки получают статус `processing` на время отправки, затем `sent`, `skipped` (недействительный токен) или `pending` для повтора. Зависшие `processing` перезахватываются через 5 минут.
- Повторные попытки события ограничены пятью; задержка растёт от 30 секунд до 10 минут. Недействительные FCM-токены деактивируются.
- Edge Function `send-price-notifications` работает через `service_role`, принимает только `POST` с заголовком `x-worker-secret`, отправляет FCM HTTP v1 и не передаёт секреты в Android.
- В уведомлении есть название товара, старая и новая цена, имя сотрудника и время. В фоновом режиме системное уведомление формирует FCM из `notification`-части; в foreground — `StorePriceMessagingService`. Время в серверной части форматируется в часовом поясе `Asia/Almaty`, в локальном уведомлении — в часовом поясе устройства.

### Гарантии и границы

- Outbox защищает от потери события между записью цены и отправкой.
- Строка доставки на устройство защищает от повторной отправки после успешного `finish`.
- FCM не гарантирует фактическую доставку: успешный ответ FCM означает только принятие запроса. Статус `sent` в БД не равен «уведомление показано пользователю».
- «Ровно один раз» через FCM невозможно гарантировать при падении процесса между принятием запроса FCM и записью результата. В этом окне возможна повторная отправка; Android-тег/идентификатор `price-<event_id>` заменяет уже показанное уведомление, но не делает доставку строго однократной.
- Push не работает без интернета; после восстановления связи каталог синхронизируется отдельно, а пропущенные события не «догоняются» на устройстве.

## 2. Подключение Firebase

1. Создайте Firebase-проект и Android-приложение с package name `com.storeprice.app`.
2. Скачайте `google-services.json` и положите его в `app/google-services.json`. Файл уже исключён из Git.
3. Убедитесь, что сборка видит конфигурацию:

```bash
source scripts/android-env.sh
./gradlew :app:assembleDebug
grep FIREBASE_CONFIGURED app/build/generated/source/buildConfig/debug/com/storeprice/app/BuildConfig.java
```

Ожидается `FIREBASE_CONFIGURED = true`. Без файла значение `false`, и приложение не пытается получить FCM-токен.

4. В Firebase Console создайте service account для Admin SDK (Project settings → Service accounts → Generate new private key). JSON-файл нужен только серверу. Не добавляйте его в `local.properties`, APK или Git.

## 3. Применение миграции

Применяйте `005` только вместе с настроенным worker, иначе события будут накапливаться в outbox без отправки. Для нового проекта порядок:

```text
202611100001_schema.sql
202611100002_rls_and_rpc.sql
202611100003_hardening.sql
202611120004_realtime.sql
202611130005_push_notifications.sql
```

`005` идемпотентна: повторный запуск не создаёт дубликаты таблиц, триггеров и функций. Локальная проверка на PostgreSQL 17 покрывает:

- создание события только при реальном изменении цены;
- исключение устройства автора и сохранение доставок на другие устройства автора;
- повторный `prepare` не создаёт дубликаты;
- `claim` переводит доставки в `processing` и не отдаёт их параллельно;
- retry, backoff и terminal `failed` после пяти попыток;
- недоступность worker RPC для `anon` и `authenticated`.

Для живого проекта:

```bash
psql "$SUPABASE_DB_URL" -v ON_ERROR_STOP=1 \
  -f supabase/migrations/202611130005_push_notifications.sql
```

Если используется Supabase CLI, миграция применится через `supabase db push` в лексикографическом порядке. Не применяйте `005` к живому проекту до создания Firebase-проекта и worker.

## 4. Деплой Edge Function и секреты

Установите и авторизуйте Supabase CLI, затем свяжите проект:

```bash
supabase link --project-ref <project-ref>
```

Секреты функции (значения не коммитить):

```bash
supabase secrets set \
  NOTIFICATION_WORKER_SECRET='<длинный-случайный-секрет>' \
  FIREBASE_SERVICE_ACCOUNT_JSON="$(cat /secure/path/firebase-service-account.json)"
```

`SUPABASE_URL` и `SUPABASE_SERVICE_ROLE_KEY` Supabase предоставляет функции автоматически. Если их нет в окружении, задайте явно только в Supabase Secrets.

Деплой:

```bash
supabase functions deploy send-price-notifications --no-verify-jwt
```

`--no-verify-jwt` отключает проверку пользовательского JWT на шлюзе; функция всё равно требует `x-worker-secret`. Не вызывайте функцию из Android-приложения: секрет worker не должен попадать в APK.

Проверка вручную:

```bash
curl -sS -X POST \
  "https://<project-ref>.supabase.co/functions/v1/send-price-notifications" \
  -H "Content-Type: application/json" \
  -H "x-worker-secret: <worker-secret>" \
  -d '{}'
```

Ожидается JSON с `processed` и списком событий. Пустой outbox даёт `{"processed":0,"events":[]}`.

## 5. Планировщик

Функцию нужно вызывать периодически. В Supabase можно использовать `pg_cron` + `pg_net`. Секрет лучше хранить в Supabase Vault, а не в тексте задания cron.

Пример с Vault (имена и значения замените):

```sql
-- Выполнить один раз в SQL Editor; секрет попадёт в Vault, не в Git.
select vault.create_secret('<worker-secret>', 'notification_worker_secret');

select cron.schedule(
  'storeprice-send-price-notifications',
  '* * * * *',
  $$
  select net.http_post(
    url := 'https://<project-ref>.supabase.co/functions/v1/send-price-notifications',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'x-worker-secret', (
        select decrypted_secret
        from vault.decrypted_secrets
        where name = 'notification_worker_secret'
      )
    ),
    body := '{}'::jsonb,
    timeout_milliseconds := 30000
  );
  $$
);
```

Проверка:

```sql
select jobid, schedule, command, active
from cron.job
where jobname = 'storeprice-send-price-notifications';

select status, count(*), min(created_at), max(created_at)
from public.notification_events
group by status;
```

Если `pg_cron`/`pg_net` недоступны, используйте внешний планировщик с тем же POST и заголовком.

## 6. Проверка на двух устройствах

1. Установить APK `0.6.0` на два устройства, войти двумя активными сотрудниками.
2. На Android 13+ разрешить уведомления.
3. На устройстве A изменить цену товара.
4. Устройство A не должно получить уведомление об этом изменении; устройство B должно получить push с названием, старой/новой ценой, именем и временем.
5. Нажать уведомление на B → открывается карточка этого товара. Проверить как при закрытом приложении, так и при открытом.
6. Повторить изменение с устройством B в роли автора → уведомление получает A.
7. Оставить приложение B в фоне/закрытым → уведомление должно появиться в системном трее.
8. Проверить журнал:

```sql
select id, status, attempts, last_error, processed_at
from public.notification_events
order by created_at desc
limit 10;

select event_id, status, attempts, last_error, sent_at
from public.notification_deliveries
order by created_at desc
limit 20;
```

9. Проверить недействительный токен: после удаления приложения или сброса данных старый токен должен получить `skipped`, а запись `device_tokens` — `is_active = false`.

## 7. Ограничения текущей реализации

- Нет Firebase-проекта, `app/google-services.json` и service account JSON; живая отправка не проверялась.
- `005` не применена к живому проекту; Edge Function не задеплоена, планировщик не настроен.
- Нет теста на реальном FCM: проверены только payload-парсер, регистрация/снятие токена и SQL-сценарий outbox.
- Уведомления о добавлении, переименовании и удалении товара не отправляются; они видны через Realtime/синхронизацию. Это соответствует первому этапу.
- Если приложение принудительно остановлено пользователем, Android может не доставить data/notification до следующего запуска; это ограничение платформы, а не сервера.
- При выходе без сети `unregister_device_token` не выполняется: серверная запись останется активной до следующего успешного входа на этом устройстве (тогда `register_device_token` деактивирует токен другого пользователя) или до явного отзыва токена. Полноценная очередь офлайн-отзыва требует WorkManager и пока не реализована.
- Время в серверном тексте фонового уведомления указано в `Asia/Almaty`; локальное уведомление в foreground использует часовой пояс устройства.
