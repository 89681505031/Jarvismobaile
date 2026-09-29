export default function handler(req, res) {
  if (req.method !== "GET") {
    res.setHeader("Allow", "GET");
    return res.status(405).json({ ok: false, error: "method_not_allowed" });
  }

  const required = [
    "WHATSAPP_VERIFY_TOKEN",
    "WHATSAPP_ACCESS_TOKEN",
    "WHATSAPP_PHONE_NUMBER_ID",
    "FISH_API_KEY",
    "FISH_VOICE_ID"
  ];

  const missing = required.filter((name) => !process.env[name]);
  const hasBrain = Boolean(process.env.JARVIS_BRAIN_URL || process.env.JARVIS_TEST_REPLY);

  return res.status(missing.length === 0 && hasBrain ? 200 : 503).json({
    ok: missing.length === 0 && hasBrain,
    service: "jarvis-whatsapp-voice-gateway",
    missing,
    brainConfigured: hasBrain,
    graphVersion: process.env.WHATSAPP_GRAPH_VERSION || "v26.0"
  });
}
