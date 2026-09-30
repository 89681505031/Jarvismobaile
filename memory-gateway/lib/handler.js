import { createHash, timingSafeEqual } from 'node:crypto';

const validId = value => typeof value === 'string' && /^[a-zA-Z0-9-]{1,64}$/.test(value);
const validText = (value, max) => typeof value === 'string' && value.trim().length > 0 && value.length <= max;
const digest = value => createHash('sha256').update(value).digest('hex');

// One gateway deployment and token belong to one person. Identity is server-owned.
export function createHandler({ getClient, env = process.env }) {
  return async function handler(req, res) {
    res.setHeader('Cache-Control', 'no-store');
    if (req.method !== 'POST') {
      res.setHeader('Allow', 'POST');
      return res.status(405).json({ error: 'Method not allowed' });
    }
    const ownerId = env.JARVIS_MEMORY_OWNER_ID || 'jarvis-mobile-user';
    const required = ['AGENT_MEMORY_BASE_URL', 'AGENT_MEMORY_STORE_ID', 'AGENT_MEMORY_API_KEY', 'JARVIS_MEMORY_TOKEN'];
    if (required.some(name => !env[name]) || env.JARVIS_MEMORY_TOKEN.length < 32 || !validId(ownerId)) {
      return res.status(503).json({ error: 'Memory service is not configured' });
    }
    const header = req.headers.authorization;
    const supplied = Buffer.from(typeof header === 'string' && header.startsWith('Bearer ') ? header.slice(7) : '');
    const expected = Buffer.from(env.JARVIS_MEMORY_TOKEN);
    if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) {
      return res.status(401).json({ error: 'Unauthorized' });
    }
    const { action, sessionId, turnId, text, answer } = req.body || {};
    if (!validId(sessionId) || !validText(text, 4000) || !['recall', 'record'].includes(action) ||
        (action === 'record' && (!validId(turnId) || !validText(answer, 8000)))) {
      return res.status(400).json({ error: 'Invalid request' });
    }
    try {
      const client = getClient();
      if (action === 'recall') {
        const result = await client.searchLongTermMemory({ text,
          filter: { ownerId: { eq: ownerId } }, filterOp: 'all', similarityThreshold: 0.7, limit: 5 });
        return res.status(200).json({ memories: result.items.slice(0, 5).map(item => item.text.slice(0, 2000)) });
      }
      const scopedSession = digest(`${ownerId}:${sessionId}`);
      // Explicit ownership makes recall work independently of promotion settings.
      const id = digest(`${ownerId}:${sessionId}:${turnId}`);
      const saved = await client.bulkCreateLongTermMemories({ memories: [{
        id, ownerId, sessionId: scopedSession, memoryType: 'episodic',
        text: `Пользователь: ${text}\nОтвет ассистента (может содержать ошибки): ${answer}`,
      }] });
      if (saved.errors?.length) throw new Error('Memory record was not saved');
      for (const [role, content, actorId] of [['USER', text, ownerId], ['ASSISTANT', answer, 'jarvis']]) {
        await client.addSessionEvent({ sessionId: scopedSession, actorId, role,
          content: [{ text: content }], createdAt: new Date() });
      }
      return res.status(200).json({ ok: true });
    } catch {
      // SDK errors may contain request bodies or headers; do not log them.
      return res.status(502).json({ error: 'Memory service unavailable' });
    }
  };
}
