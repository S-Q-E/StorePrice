import { createClient, type SupabaseClient } from "npm:@supabase/supabase-js@2.47.10";
import { cert, getApps, initializeApp } from "npm:firebase-admin@12.7.0/app";
import { getMessaging } from "npm:firebase-admin@12.7.0/messaging";

/**
 * StorePrice push worker.
 *
 * The database trigger writes a notification event and one delivery row per
 * active recipient device inside the same transaction as price_history. This
 * function only claims pending work, sends it through FCM HTTP v1 and records
 * the result. Delivery rows make retries safe: a recipient that was already
 * marked `sent` is never sent the same event again.
 *
 * Required environment variables:
 * - SUPABASE_URL
 * - SUPABASE_SERVICE_ROLE_KEY
 * - NOTIFICATION_WORKER_SECRET
 * - FIREBASE_SERVICE_ACCOUNT_JSON
 */

type NotificationEvent = {
  id: string;
  product_id: string;
  product_name: string;
  old_price_kzt: number;
  new_price_kzt: number;
  changed_by_name: string;
  changed_at: string;
  attempts: number;
};

type Delivery = {
  id: string;
  device_token_id: string;
  fcm_token: string;
};

const SUPABASE_URL = Deno.env.get("SUPABASE_URL") ?? "";
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
const WORKER_SECRET = Deno.env.get("NOTIFICATION_WORKER_SECRET") ?? "";
const FIREBASE_SERVICE_ACCOUNT_JSON =
  Deno.env.get("FIREBASE_SERVICE_ACCOUNT_JSON") ?? "";

const EVENT_BATCH_SIZE = 20;
const DELIVERY_BATCH_SIZE = 500;
const MAX_DELIVERY_ATTEMPTS = 5;
const STALE_JOB_RETRY_SECONDS = 60;

Deno.serve(async (request) => {
  if (request.method !== "POST") {
    return json({ error: "Method not allowed" }, 405);
  }
  if (!WORKER_SECRET || request.headers.get("x-worker-secret") !== WORKER_SECRET) {
    return json({ error: "Unauthorized" }, 401);
  }
  if (!SUPABASE_URL || !SERVICE_ROLE_KEY) {
    return json({ error: "Supabase worker configuration is missing" }, 500);
  }

  let firebaseReady = false;
  try {
    ensureFirebase();
    firebaseReady = true;
  } catch (error) {
    console.error("Firebase initialization failed", error);
  }

  const supabase = createClient(SUPABASE_URL, SERVICE_ROLE_KEY, {
    auth: { persistSession: false, autoRefreshToken: false },
  });

  const { data: events, error: claimError } = await supabase.rpc(
    "claim_notification_events",
    { p_limit: EVENT_BATCH_SIZE },
  );
  if (claimError) {
    console.error("Could not claim notification events", claimError);
    return json({ error: "Could not claim events" }, 500);
  }

  const results: Array<{ eventId: string; status: string; deliveries: number }> = [];

  for (const event of (events ?? []) as NotificationEvent[]) {
    try {
      if (!firebaseReady) {
        await scheduleRetry(supabase, event, "Firebase is not configured");
        results.push({ eventId: event.id, status: "retry", deliveries: 0 });
        continue;
      }

      const { error: prepareError } = await supabase.rpc(
        "prepare_notification_deliveries",
        { p_event_id: event.id },
      );
      if (prepareError) throw prepareError;

      const { data: deliveries, error: deliveryError } = await supabase.rpc(
        "claim_notification_deliveries",
        { p_event_id: event.id, p_limit: DELIVERY_BATCH_SIZE },
      );
      if (deliveryError) throw deliveryError;

      const claimed = (deliveries ?? []) as Delivery[];
      for (const chunk of chunked(claimed, DELIVERY_BATCH_SIZE)) {
        await sendChunk(supabase, event, chunk);
      }

      const { data: status, error: finishError } = await supabase.rpc(
        "finish_notification_event",
        { p_event_id: event.id },
      );
      if (finishError) throw finishError;

      results.push({
        eventId: event.id,
        status: String(status ?? "unknown"),
        deliveries: claimed.length,
      });
    } catch (error) {
      console.error(`Notification event ${event.id} failed`, error);
      await scheduleRetry(supabase, event, errorMessage(error));
      results.push({ eventId: event.id, status: "retry", deliveries: 0 });
    }
  }

  return json({ processed: results.length, events: results }, 200);
});

function ensureFirebase(): void {
  if (getApps().length > 0) return;
  if (!FIREBASE_SERVICE_ACCOUNT_JSON) {
    throw new Error("FIREBASE_SERVICE_ACCOUNT_JSON is missing");
  }
  const serviceAccount = JSON.parse(FIREBASE_SERVICE_ACCOUNT_JSON);
  initializeApp({ credential: cert(serviceAccount) });
}

async function sendChunk(
  supabase: SupabaseClient,
  event: NotificationEvent,
  deliveries: Delivery[],
): Promise<void> {
  if (deliveries.length === 0) return;

  const response = await getMessaging().sendEachForMulticast({
    tokens: deliveries.map((delivery) => delivery.fcm_token),
    notification: {
      title: "Изменение цены",
      body: notificationBody(event),
    },
    data: {
      event_id: event.id,
      product_id: event.product_id,
      product_name: event.product_name,
      old_price_kzt: String(event.old_price_kzt),
      new_price_kzt: String(event.new_price_kzt),
      changed_by_name: event.changed_by_name,
      changed_at: event.changed_at,
    },
    android: {
      priority: "high",
      notification: {
        channel_id: "price_changes",
        icon: "ic_notification",
        sound: "default",
        tag: `price-${event.id}`,
      },
    },
  });

  for (let index = 0; index < deliveries.length; index += 1) {
    const delivery = deliveries[index];
    const result = response.responses[index];

    if (result?.success) {
      await finishDelivery(supabase, delivery.id, true, false, null);
      continue;
    }

    const code = result?.error?.code ?? "messaging/unknown-error";
    const permanent = isPermanentTokenError(code);
    if (permanent) {
      await supabase
        .from("device_tokens")
        .update({ is_active: false })
        .eq("id", delivery.device_token_id)
        .eq("is_active", true);
    }
    await finishDelivery(
      supabase,
      delivery.id,
      false,
      permanent,
      `${code}: ${result?.error?.message ?? "FCM rejected the token"}`,
    );
  }
}

async function finishDelivery(
  supabase: SupabaseClient,
  deliveryId: string,
  success: boolean,
  permanent: boolean,
  error: string | null,
): Promise<void> {
  const { error: finishError } = await supabase.rpc(
    "finish_notification_delivery",
    {
      p_delivery_id: deliveryId,
      p_success: success,
      p_permanent: permanent,
      p_error: error,
    },
  );
  if (finishError) {
    console.error(`Could not finish delivery ${deliveryId}`, finishError);
  }
}

async function scheduleRetry(
  supabase: SupabaseClient,
  event: NotificationEvent,
  error: string,
): Promise<void> {
  const terminal = event.attempts >= MAX_DELIVERY_ATTEMPTS;
  const nextAttemptAt = terminal
    ? new Date().toISOString()
    : new Date(Date.now() + STALE_JOB_RETRY_SECONDS * 1000).toISOString();

  const { error: updateError } = await supabase
    .from("notification_events")
    .update({
      status: terminal ? "failed" : "pending",
      next_attempt_at: nextAttemptAt,
      last_error: error.slice(0, 2000),
      processed_at: terminal ? new Date().toISOString() : null,
      updated_at: new Date().toISOString(),
    })
    .eq("id", event.id);

  if (updateError) {
    console.error(`Could not schedule retry for event ${event.id}`, updateError);
  }
}

function notificationBody(event: NotificationEvent): string {
  return `${event.product_name}: ${formatKzt(event.old_price_kzt)} → ` +
    `${formatKzt(event.new_price_kzt)}. Изменил: ${event.changed_by_name}, ` +
    formatChangedAt(event.changed_at);
}

function formatChangedAt(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "время не указано";
  return new Intl.DateTimeFormat("ru-RU", {
    timeZone: "Asia/Almaty",
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}

function formatKzt(amount: number): string {
  return `${new Intl.NumberFormat("ru-RU").format(amount)} ₸`;
}

function isPermanentTokenError(code: string): boolean {
  return code === "messaging/registration-token-not-registered" ||
    code === "messaging/invalid-registration-token" ||
    code === "messaging/invalid-argument";
}

function chunked<T>(items: T[], size: number): T[][] {
  const chunks: T[][] = [];
  for (let index = 0; index < items.length; index += size) {
    chunks.push(items.slice(index, index + size));
  }
  return chunks;
}

function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (typeof error === "string") return error;
  try {
    return JSON.stringify(error);
  } catch {
    return "Unknown notification worker error";
  }
}

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json; charset=utf-8" },
  });
}
