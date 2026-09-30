const BRAIN_TIMEOUT_MS = 3200;
const MAX_TEXT_LENGTH = 1024;

function parseBody(body) {
  if (!body) return {};
  if (typeof body === "string") return JSON.parse(body);
  if (Buffer.isBuffer(body)) return JSON.parse(body.toString("utf8"));
  return body;
}

function cleanForAlice(value) {
  return String(value || "")
    .replace(/\*\*(.*?)\*\*/g, "$1")
    .replace(/__(.*?)__/g, "$1")
    .replace(/^\s*#{1,6}\s+/gm, "")
    .replace(/^\s*[-*]\s+/gm, "")
    .replace(/`{1,3}/g, "")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, MAX_TEXT_LENGTH);
}

function aliceResponse(text, { endSession = false, sessionState } = {}) {
  const safeText = cleanForAlice(text) || "Сэр, я вас слушаю.";

  const payload = {
    response: {
      text: safeText,
      tts: safeText,
      end_session: endSession
    },
    version: "1.0"
  };

  if (sessionState) {
    payload.session_state = sessionState;
  }

  return payload;
}

async function fetchWithTimeout(url, options = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), BRAIN_TIMEOUT_MS);

  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

function userKey(payload) {
  const userId = payload?.session?.user?.user_id;
  const applicationId = payload?.session?.application?.application_id;
  const sessionId = payload?.session?.session_id;

  return `alice:${userId || applicationId || sessionId || "anonymous"}`;
}

function messageKey(payload) {
  const sessionId = payload?.session?.session_id || "unknown-session";
  const messageId = payload?.session?.message_id ?? 0;
  return `alice:${sessionId}:${messageId}`;
}

function isAllowedSkill(payload) {
  const expected = process.env.YANDEX_ALICE_SKILL_ID;
  if (!expected) return true;
  return payload?.session?.skill_id === expected;
}

async function generateJarvisReply(payload, text) {
  const brainUrl = process.env.JARVIS_BRAIN_URL;

  if (!brainUrl) {
    if (process.env.JARVIS_TEST_REPLY) {
      return process.env.JARVIS_TEST_REPLY;
    }

    return "Сэр, шлюз Алисы уже работает, но адрес мозга Джарвиса пока не настроен.";
  }

  const headers = {
    "Content-Type": "application/json"
  };

  if (process.env.JARVIS_BRAIN_TOKEN) {
    headers.Authorization = `Bearer ${process.env.JARVIS_BRAIN_TOKEN}`;
  }

  const response = await fetchWithTimeout(brainUrl, {
    method: "POST",
    headers,
    body: JSON.stringify({
      text,
      from: userKey(payload),
      channel: "alice",
      message_id: messageKey(payload),
      locale: payload?.meta?.locale || "ru-RU",
      timezone: payload?.meta?.timezone || "",
      session_id: payload?.session?.session_id || ""
    })
  });

  if (!response.ok) {
    throw new Error(`Jarvis brain failed: ${response.status} ${await response.text()}`);
  }

  const contentType = response.headers.get("content-type") || "";

  if (contentType.includes("application/json")) {
    const data = await response.json();
    const reply = data?.reply ?? data?.text ?? data?.message;

    if (!reply || typeof reply !== "string") {
      throw new Error("Jarvis brain returned JSON without reply/text/message");
    }

    return reply;
  }

  const reply = (await response.text()).trim();
  if (!reply) throw new Error("Jarvis brain returned an empty reply");
  return reply;
}

export default async function handler(req, res) {
  if (req.method === "GET") {
    return res.status(200).json({
      ok: true,
      service: "jarvis-alice-gateway",
      brainConfigured: Boolean(process.env.JARVIS_BRAIN_URL || process.env.JARVIS_TEST_REPLY),
      skillIdRestricted: Boolean(process.env.YANDEX_ALICE_SKILL_ID),
      brainTimeoutMs: BRAIN_TIMEOUT_MS
    });
  }

  if (req.method !== "POST") {
    res.setHeader("Allow", "GET, POST");
    return res.status(405).json({ ok: false, error: "method_not_allowed" });
  }

  let payload;

  try {
    payload = parseBody(req.body);
  } catch {
    return res.status(400).json(aliceResponse("Сэр, я не смог разобрать запрос Алисы."));
  }

  if (!isAllowedSkill(payload)) {
    return res.status(403).json({ ok: false, error: "skill_not_allowed" });
  }

  const original = payload?.request?.original_utterance?.trim();
  const command = payload?.request?.command?.trim();
  const utterance = original || command || "";

  if (payload?.session?.new && !utterance) {
    return res.status(200).json(
      aliceResponse(
        "Джарвис на связи, сэр. Я подключён к тому же мозгу, что и Джарвис Мобайл. Что прикажете?",
        { sessionState: { channel: "alice" } }
      )
    );
  }

  if (!utterance) {
    return res.status(200).json(
      aliceResponse("Сэр, повторите команду.", { sessionState: { channel: "alice" } })
    );
  }

  const normalized = utterance.toLowerCase();
  if (["выход", "закончить", "закрой джарвис", "закрыть джарвис"].includes(normalized)) {
    return res.status(200).json(aliceResponse("До связи, сэр.", { endSession: true }));
  }

  try {
    const reply = await generateJarvisReply(payload, utterance);
    return res.status(200).json(
      aliceResponse(reply, { sessionState: { channel: "alice" } })
    );
  } catch (error) {
    console.error("Alice gateway error:", error);

    const timeout =
      error?.name === "AbortError" ||
      String(error?.message || "").toLowerCase().includes("aborted");

    const fallback = timeout
      ? "Сэр, мозг Джарвиса отвечает слишком долго. Повторите команду."
      : "Сэр, сейчас не удалось связаться с мозгом Джарвиса. Повторите команду.";

    return res.status(200).json(
      aliceResponse(fallback, { sessionState: { channel: "alice" } })
    );
  }
}
