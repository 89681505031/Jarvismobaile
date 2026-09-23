import { createServer } from 'node:http';
import { createHmac, timingSafeEqual } from 'node:crypto';

const MAX_BODY = 64 * 1024;
const MAX_INBOX = 200;
const TTL_MS = 23 * 60 * 60 * 1000;
const safeText = value => typeof value === 'string' && value.trim().length > 0 && value.length <= 1000;
const safeId = value => typeof value === 'string' && /^[a-zA-Z0-9_.:$-]{1,255}$/.test(value);
const equal = (a, b) => {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const x = Buffer.from(a), y = Buffer.from(b);
  return x.length === y.length && timingSafeEqual(x, y);
};
function json(res, status, data) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(data));
}
async function body(req) {
  let length = 0;
  const chunks = [];
  for await (const chunk of req) {
    length += chunk.length;
    if (length > MAX_BODY) { const error = new Error('too large'); error.status = 413; throw error; }
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}
function verified(raw, signature, secret) {
  if (!secret || typeof signature !== 'string' || !/^sha256=[a-f0-9]{64}$/.test(signature)) return false;
  return equal(signature, 'sha256=' + createHmac('sha256', secret).update(raw).digest('hex'));
}

/**
 * Pilot gateway: only verified incoming DMs are kept in short-lived process memory.
 * Never auto-send; the owner must explicitly approve each outgoing message.
 * Production requires a persistent, encrypted inbox and idempotent sending.
 */
export function createApp({ env = process.env, fetchImpl = fetch, clock = () => Date.now() } = {}) {
  const inbox = new Map();
  const configured = () => !!(env.INSTAGRAM_APP_SECRET && env.INSTAGRAM_WEBHOOK_VERIFY_TOKEN &&
    env.INSTAGRAM_ACCOUNT_ID && env.INSTAGRAM_ACCOUNT_TOKEN &&
    env.JARVIS_INSTAGRAM_ADMIN_TOKEN?.length >= 32);
  const admin = req => equal(req.headers.authorization, 'Bearer ' + (env.JARVIS_INSTAGRAM_ADMIN_TOKEN || ''));
  const prune = () => {
    for (const [id, item] of inbox) if (clock() - item.receivedAt > TTL_MS) inbox.delete(id);
    while (inbox.size > MAX_INBOX) inbox.delete(inbox.keys().next().value);
  };
  return async function app(req, res) {
    const url = new URL(req.url || '/', 'http://localhost');
    const path = url.pathname;
    try {
      if (req.method === 'GET' && path === '/health') {
        return json(res, 200, { service: 'jarvis-instagram', configured: configured(),
          mode: 'human-approval-pilot', persistentInbox: false });
      }
      if (req.method === 'GET' && path === '/webhook') {
        if (!env.INSTAGRAM_WEBHOOK_VERIFY_TOKEN || env.INSTAGRAM_WEBHOOK_VERIFY_TOKEN.length < 32) {
          return json(res, 503, { error: 'Webhook not configured' });
        }
        const mode = url.searchParams.get('hub.mode');
        const challenge = url.searchParams.get('hub.challenge');
        const verifyToken = url.searchParams.get('hub.verify_token');
        if (mode !== 'subscribe' || !equal(verifyToken, env.INSTAGRAM_WEBHOOK_VERIFY_TOKEN) ||
            !challenge || !/^[0-9A-Za-z._-]{1,255}$/.test(challenge)) {
          return json(res, 403, { error: 'Verification failed' });
        }
        res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' });
        return res.end(challenge);
      }
      if (req.method === 'POST' && path === '/webhook') {
        if (!env.INSTAGRAM_APP_SECRET || !env.INSTAGRAM_ACCOUNT_ID) {
          return json(res, 503, { error: 'Webhook not configured' });
        }
        const raw = await body(req);
        if (!verified(raw, req.headers['x-hub-signature-256'], env.INSTAGRAM_APP_SECRET)) {
          return json(res, 401, { error: 'Invalid signature' });
        }
        let payload;
        try { payload = JSON.parse(raw.toString('utf8')); }
        catch { return json(res, 400, { error: 'Invalid JSON' }); }
        if (payload?.object !== 'instagram' || !Array.isArray(payload.entry)) {
          return json(res, 400, { error: 'Unexpected webhook payload' });
        }
        prune();
        for (const entry of payload.entry) {
          for (const event of (Array.isArray(entry.messaging) ? entry.messaging : [])) {
            const id = event?.message?.mid, text = event?.message?.text;
            const senderId = event?.sender?.id, accountId = event?.recipient?.id;
            if (event?.message?.is_echo || !safeId(id) || !safeText(text) ||
                !safeId(senderId) || String(accountId) !== String(env.INSTAGRAM_ACCOUNT_ID) ||
                senderId === String(env.INSTAGRAM_ACCOUNT_ID) || inbox.has(id)) continue;
            inbox.set(id, { id, senderId, text: text.trim(), receivedAt: clock(), state: 'pending' });
          }
        }
        prune();
        return json(res, 200, { ok: true });
      }
      if (path.startsWith('/admin/')) {
        if (!configured()) return json(res, 503, { error: 'Gateway not configured' });
        if (!admin(req)) return json(res, 401, { error: 'Unauthorized' });
        prune();
        if (req.method === 'GET' && path === '/admin/inbox') {
          return json(res, 200, { items: [...inbox.values()].filter(item => item.state === 'pending')
            .map(({ id, text, receivedAt }) => ({ id, text, receivedAt })) });
        }
        if (req.method === 'POST' && path === '/admin/reply') {
          let input;
          try { input = JSON.parse((await body(req)).toString('utf8')); }
          catch (err) { if (err.status) throw err; return json(res, 400, { error: 'Invalid JSON' }); }
          if (!safeId(input?.messageId) || !safeText(input?.text)) {
            return json(res, 400, { error: 'Invalid reply' });
          }
          const item = inbox.get(input.messageId);
          if (!item || item.state !== 'pending') return json(res, 409, { error: 'Message unavailable' });
          item.state = 'sending';
          const version = /^v[0-9]+\.[0-9]+$/.test(env.INSTAGRAM_GRAPH_VERSION || '') ?
            env.INSTAGRAM_GRAPH_VERSION : 'v26.0';
          let upstream;
          try {
            upstream = await fetchImpl(`https://graph.instagram.com/${version}/${encodeURIComponent(env.INSTAGRAM_ACCOUNT_ID)}/messages`, {
              method: 'POST',
              headers: { Authorization: 'Bearer ' + env.INSTAGRAM_ACCOUNT_TOKEN,
                'Content-Type': 'application/json' },
              body: JSON.stringify({ recipient: { id: item.senderId }, message: { text: input.text.trim() } }),
              signal: AbortSignal.timeout(10000),
            });
          } catch {
            // The provider may have delivered the message before a network failure. No blind retry.
            item.state = 'unknown';
            return json(res, 502, { error: 'Delivery unconfirmed; check Instagram inbox before retrying' });
          }
          if (!upstream.ok) {
            item.state = 'pending'; // Provider explicitly rejected the request.
            return json(res, 502, { error: 'Instagram rejected the reply; check permissions and messaging window' });
          }
          item.state = 'sent';
          return json(res, 200, { ok: true });
        }
      }
      return json(res, 404, { error: 'Not found' });
    } catch (err) {
      return json(res, err.status === 413 ? 413 : 500,
        { error: err.status === 413 ? 'Request too large' : 'Request failed' });
    }
  };
}

if (process.argv[1] && import.meta.url === new URL('file://' + process.argv[1]).href) {
  const port = Number(process.env.PORT || 8080);
  createServer(createApp()).listen(port, '0.0.0.0', () => {
    console.log('Jarvis Instagram gateway listening on port ' + port);
  });
}
