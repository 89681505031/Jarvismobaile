import "jsr:@supabase/functions-js/edge-runtime.d.ts";

declare const EdgeRuntime: {
  waitUntil(promise: Promise<unknown>): void;
};

const DEFAULT_GRAPH_VERSION = "v26.0";
const REQUEST_TIMEOUT_MS = 15000;

function env(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Missing environment variable: ${name}`);
  return value;
}

function optional(name: string): string {
  return Deno.env.get(name) ?? "";
}

function graphVersion(): string {
  return optional("WHATSAPP_GRAPH_VERSION") || DEFAULT_GRAPH_VERSION;
}

function graphUrl(phoneNumberId: string, resource: string): string {
  return `https://graph.facebook.com/${graphVersion()}/${phoneNumberId}/${resource}`;
}

function isAllowedSender(from: string): boolean {
  if (optional("WHATSAPP_ALLOW_ALL").toLowerCase() === "true") return true;

  const allowed = optional("WHATSAPP_ALLOWED_NUMBERS")
    .split(",")
    .map((v) => v.replace(/\D/g, ""))
    .filter(Boolean);

  return allowed.includes(String(from || "").replace(/\D/g, ""));
}

function collectIncomingMessages(payload: any) {
  const output: Array<{ message: any; phoneNumberId?: string }> = [];

  for (const entry of payload?.entry || []) {
    for (const change of entry?.changes || []) {
      const value = change?.value;
      const phoneNumberId = value?.metadata?.phone_number_id;
      for (const message of value?.messages || []) {
        output.push({ message, phoneNumberId });
      }
    }
  }

  return output;
}

async function fetchWithTimeout(url: string, options: RequestInit = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

async function verifyMetaSignature(rawBody: string, signatureHeader: string | null): Promise<boolean> {
  const secret = optional("WHATSAPP_APP_SECRET");
  if (!secret || !signatureHeader?.startsWith("sha256=")) return false;

  const key = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );

  const digest = new Uint8Array(
    await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(rawBody)),
  );

  const expected =
    "sha256=" +
    Array.from(digest)
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");

  if (expected.length !== signatureHeader.length) return false;

  let mismatch = 0;
  for (let i = 0; i < expected.length; i++) {
    mismatch |= expected.charCodeAt(i) ^ signatureHeader.charCodeAt(i);
  }
  return mismatch === 0;
}

async function generateJarvisReply(input: {
  text: string;
  from: string;
  messageId: string;
}): Promise<string> {
  const brainUrl = optional("JARVIS_BRAIN_URL");

  if (!brainUrl) {
    const testReply = optional("JARVIS_TEST_REPLY");
    if (testReply) return testReply;
    throw new Error("JARVIS_BRAIN_URL or JARVIS_TEST_REPLY must be configured");
  }

  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };

  const brainToken = optional("JARVIS_BRAIN_TOKEN");
  if (brainToken) headers.Authorization = `Bearer ${brainToken}`;

  const response = await fetchWithTimeout(brainUrl, {
    method: "POST",
    headers,
    body: JSON.stringify({
      text: input.text,
      from: input.from,
      channel: "whatsapp",
      message_id: input.messageId,
    }),
  });

  if (!response.ok) {
    throw new Error(`Jarvis brain failed: ${response.status} ${await response.text()}`);
  }

  const type = response.headers.get("content-type") || "";
  if (type.includes("application/json")) {
    const data = await response.json();
    const reply = data?.reply ?? data?.text ?? data?.message;
    if (!reply || typeof reply !== "string") {
      throw new Error("Jarvis brain returned JSON without reply/text/message");
    }
    return reply.trim();
  }

  const reply = (await response.text()).trim();
  if (!reply) throw new Error("Jarvis brain returned empty reply");
  return reply;
}

async function synthesizeJarvisVoice(text: string): Promise<Uint8Array> {
  const apiKey = env("FISH_API_KEY");
  const voiceId = env("FISH_VOICE_ID");

  const response = await fetchWithTimeout("https://api.fish.audio/v1/tts", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
      model: optional("FISH_MODEL") || "s2.1-pro-free",
    },
    body: JSON.stringify({
      text,
      reference_id: voiceId,
      format: "opus",
    }),
  });

  if (!response.ok) {
    throw new Error(`Fish Audio TTS failed: ${response.status} ${await response.text()}`);
  }

  const bytes = new Uint8Array(await response.arrayBuffer());
  const magic = new TextDecoder().decode(bytes.slice(0, 4));

  if (magic !== "OggS") {
    throw new Error("Fish Audio response is not an OGG/Opus container");
  }

  return bytes;
}

async function uploadVoice(audio: Uint8Array, phoneNumberId: string): Promise<string> {
  const form = new FormData();
  form.append("messaging_product", "whatsapp");
  form.append(
    "file",
    new Blob([audio], { type: "audio/ogg; codecs=opus" }),
    "jarvis-reply.ogg",
  );

  const response = await fetchWithTimeout(graphUrl(phoneNumberId, "media"), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env("WHATSAPP_ACCESS_TOKEN")}`,
    },
    body: form,
  });

  if (!response.ok) {
    throw new Error(`WhatsApp media upload failed: ${response.status} ${await response.text()}`);
  }

  const data = await response.json();
  if (!data?.id) throw new Error("WhatsApp media upload did not return media id");
  return data.id;
}

async function sendVoiceReply(args: {
  to: string;
  messageId: string;
  mediaId: string;
  phoneNumberId: string;
}) {
  const response = await fetchWithTimeout(graphUrl(args.phoneNumberId, "messages"), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${env("WHATSAPP_ACCESS_TOKEN")}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      messaging_product: "whatsapp",
      recipient_type: "individual",
      to: args.to,
      context: { message_id: args.messageId },
      type: "audio",
      audio: {
        id: args.mediaId,
        voice: true,
      },
    }),
  });

  if (!response.ok) {
    throw new Error(`WhatsApp send failed: ${response.status} ${await response.text()}`);
  }
}

async function processTextMessage(message: any, phoneNumberId?: string) {
  const from = message?.from;
  const text = message?.text?.body?.trim();
  const messageId = message?.id;

  if (!from || !text || !messageId) return;

  if (!isAllowedSender(from)) {
    console.log("Ignoring sender not in allow-list");
    return;
  }

  const pid = phoneNumberId || env("WHATSAPP_PHONE_NUMBER_ID");
  const reply = await generateJarvisReply({ text, from, messageId });
  const voice = await synthesizeJarvisVoice(reply);
  const mediaId = await uploadVoice(voice, pid);

  await sendVoiceReply({
    to: from,
    messageId,
    mediaId,
    phoneNumberId: pid,
  });
}

async function processPayload(payload: any) {
  const incoming = collectIncomingMessages(payload);

  for (const { message, phoneNumberId } of incoming) {
    if (message?.type === "text") {
      try {
        await processTextMessage(message, phoneNumberId);
      } catch (error) {
        console.error("Jarvis WhatsApp processing error:", error);
      }
    }
  }
}

function json(data: unknown, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

Deno.serve(async (req: Request) => {
  const url = new URL(req.url);

  if (req.method === "GET" && url.searchParams.get("health") === "1") {
    const required = [
      "WHATSAPP_VERIFY_TOKEN",
      "WHATSAPP_ACCESS_TOKEN",
      "WHATSAPP_PHONE_NUMBER_ID",
      "WHATSAPP_APP_SECRET",
      "FISH_API_KEY",
      "FISH_VOICE_ID",
    ];

    const missing = required.filter((name) => !optional(name));
    const brainConfigured = Boolean(optional("JARVIS_BRAIN_URL") || optional("JARVIS_TEST_REPLY"));

    return json({
      ok: missing.length === 0 && brainConfigured,
      service: "jarvis-whatsapp-webhook",
      missing,
      brainConfigured,
      senderPolicyConfigured: Boolean(
        optional("WHATSAPP_ALLOWED_NUMBERS") ||
          optional("WHATSAPP_ALLOW_ALL").toLowerCase() === "true",
      ),
      graphVersion: graphVersion(),
    }, missing.length === 0 && brainConfigured ? 200 : 503);
  }

  if (req.method === "GET") {
    const mode = url.searchParams.get("hub.mode");
    const token = url.searchParams.get("hub.verify_token");
    const challenge = url.searchParams.get("hub.challenge");

    if (
      mode === "subscribe" &&
      token &&
      challenge &&
      token === optional("WHATSAPP_VERIFY_TOKEN")
    ) {
      return new Response(challenge, { status: 200 });
    }

    return json({ ok: false, error: "verification_failed" }, 403);
  }

  if (req.method !== "POST") {
    return json({ ok: false, error: "method_not_allowed" }, 405);
  }

  const rawBody = await req.text();

  const signatureOk = await verifyMetaSignature(
    rawBody,
    req.headers.get("x-hub-signature-256"),
  );

  if (!signatureOk) {
    console.error("Rejected webhook with invalid or missing Meta signature");
    return json({ ok: false, error: "invalid_signature" }, 401);
  }

  let payload: any;
  try {
    payload = JSON.parse(rawBody);
  } catch {
    return json({ ok: false, error: "invalid_json" }, 400);
  }

  EdgeRuntime.waitUntil(processPayload(payload));
  return json({ ok: true });
});
