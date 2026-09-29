const DEFAULT_GRAPH_VERSION = "v26.0";
const REQUEST_TIMEOUT_MS = 15000;

function env(name) {
  const value = process.env[name];
  if (!value) throw new Error(`Missing environment variable: ${name}`);
  return value;
}

function graphVersion() {
  return process.env.WHATSAPP_GRAPH_VERSION || DEFAULT_GRAPH_VERSION;
}

function graphUrl(phoneNumberId, resource) {
  return `https://graph.facebook.com/${graphVersion()}/${phoneNumberId}/${resource}`;
}

function isAllowedSender(from) {
  if (process.env.WHATSAPP_ALLOW_ALL === "true") return true;

  const allowed = (process.env.WHATSAPP_ALLOWED_NUMBERS || "")
    .split(",")
    .map((value) => value.replace(/\D/g, ""))
    .filter(Boolean);

  return allowed.includes(String(from || "").replace(/\D/g, ""));
}

function collectIncomingMessages(payload) {
  const output = [];

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

async function fetchWithTimeout(url, options = {}) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } finally {
    clearTimeout(timer);
  }
}

async function generateJarvisReply({ text, from, messageId }) {
  const brainUrl = process.env.JARVIS_BRAIN_URL;

  if (!brainUrl) {
    const testReply = process.env.JARVIS_TEST_REPLY;
    if (testReply) return testReply;
    throw new Error("JARVIS_BRAIN_URL or JARVIS_TEST_REPLY must be configured");
  }

  const headers = { "Content-Type": "application/json" };
  if (process.env.JARVIS_BRAIN_TOKEN) {
    headers.Authorization = `Bearer ${process.env.JARVIS_BRAIN_TOKEN}`;
  }

  const response = await fetchWithTimeout(brainUrl, {
    method: "POST",
    headers,
    body: JSON.stringify({
      text,
      from,
      channel: "whatsapp",
      message_id: messageId
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
    return reply.trim();
  }

  const reply = (await response.text()).trim();
  if (!reply) throw new Error("Jarvis brain returned an empty reply");
  return reply;
}

async function synthesizeJarvisVoice(text) {
  const apiKey = env("FISH_API_KEY");
  const voiceId = env("FISH_VOICE_ID");

  const response = await fetchWithTimeout("https://api.fish.audio/v1/tts", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${apiKey}`,
      "Content-Type": "application/json",
      model: process.env.FISH_MODEL || "s2.1-pro-free"
    },
    body: JSON.stringify({
      text,
      reference_id: voiceId,
      format: "opus"
    })
  });

  if (!response.ok) {
    throw new Error(`Fish Audio TTS failed: ${response.status} ${await response.text()}`);
  }

  const bytes = new Uint8Array(await response.arrayBuffer());

  // WhatsApp voice messages require an OGG container with Opus audio.
  const magic = String.fromCharCode(...bytes.slice(0, 4));
  if (magic !== "OggS") {
    throw new Error(
      "Fish Audio response is not OGG/Opus. Expected an OggS container for WhatsApp voice notes."
    );
  }

  return bytes;
}

async function uploadVoiceToWhatsApp(audioBytes, phoneNumberId) {
  const token = env("WHATSAPP_ACCESS_TOKEN");
  const form = new FormData();

  form.append("messaging_product", "whatsapp");
  form.append(
    "file",
    new Blob([audioBytes], { type: "audio/ogg; codecs=opus" }),
    "jarvis-reply.ogg"
  );

  const response = await fetchWithTimeout(graphUrl(phoneNumberId, "media"), {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body: form
  });

  if (!response.ok) {
    throw new Error(`WhatsApp media upload failed: ${response.status} ${await response.text()}`);
  }

  const data = await response.json();
  if (!data?.id) throw new Error("WhatsApp media upload did not return a media id");
  return data.id;
}

async function sendVoiceReply({ to, replyToMessageId, mediaId, phoneNumberId }) {
  const token = env("WHATSAPP_ACCESS_TOKEN");

  const response = await fetchWithTimeout(graphUrl(phoneNumberId, "messages"), {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      messaging_product: "whatsapp",
      recipient_type: "individual",
      to,
      context: { message_id: replyToMessageId },
      type: "audio",
      audio: {
        id: mediaId,
        voice: true
      }
    })
  });

  if (!response.ok) {
    throw new Error(`WhatsApp send failed: ${response.status} ${await response.text()}`);
  }

  return response.json();
}

async function processTextMessage(message, phoneNumberId) {
  const from = message?.from;
  const text = message?.text?.body?.trim();

  if (!from || !text || !message?.id) return;
  if (!isAllowedSender(from)) {
    console.log("Ignoring WhatsApp sender not in allow-list:", from);
    return;
  }

  if (!phoneNumberId) {
    phoneNumberId = env("WHATSAPP_PHONE_NUMBER_ID");
  }

  const reply = await generateJarvisReply({
    text,
    from,
    messageId: message.id
  });

  const voice = await synthesizeJarvisVoice(reply);
  const mediaId = await uploadVoiceToWhatsApp(voice, phoneNumberId);

  await sendVoiceReply({
    to: from,
    replyToMessageId: message.id,
    mediaId,
    phoneNumberId
  });
}

export default async function handler(req, res) {
  if (req.method === "GET") {
    const mode = req.query?.["hub.mode"];
    const verifyToken = req.query?.["hub.verify_token"];
    const challenge = req.query?.["hub.challenge"];

    if (mode === "subscribe" && verifyToken === process.env.WHATSAPP_VERIFY_TOKEN) {
      return res.status(200).send(challenge);
    }

    return res.status(403).json({ ok: false, error: "verification_failed" });
  }

  if (req.method !== "POST") {
    res.setHeader("Allow", "GET, POST");
    return res.status(405).json({ ok: false, error: "method_not_allowed" });
  }

  try {
    const payload =
      typeof req.body === "string"
        ? JSON.parse(req.body)
        : Buffer.isBuffer(req.body)
          ? JSON.parse(req.body.toString("utf8"))
          : req.body;

    const incoming = collectIncomingMessages(payload);

    for (const { message, phoneNumberId } of incoming) {
      // First production milestone: incoming text -> Jarvis voice note.
      // Audio/STT support is intentionally handled in the next module.
      if (message?.type === "text") {
        await processTextMessage(message, phoneNumberId);
      }
    }

    return res.status(200).json({ ok: true });
  } catch (error) {
    console.error("WhatsApp webhook error:", error);
    // Acknowledge receipt to avoid repeated webhook retries while logging the fault.
    return res.status(200).json({ ok: false, error: "processing_failed" });
  }
}
