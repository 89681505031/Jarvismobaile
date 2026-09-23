import assert from 'node:assert/strict';
import { createHmac } from 'node:crypto';
import { createServer } from 'node:http';
import test from 'node:test';
import { createApp } from '../server.js';

const env = {
  INSTAGRAM_APP_SECRET: 'b'.repeat(48),
  INSTAGRAM_WEBHOOK_VERIFY_TOKEN: 'v'.repeat(48),
  INSTAGRAM_ACCOUNT_ID: '179123',
  INSTAGRAM_ACCOUNT_TOKEN: 'account-test-token',
  JARVIS_INSTAGRAM_ADMIN_TOKEN: 'a'.repeat(48),
  INSTAGRAM_GRAPH_VERSION: 'v26.0',
};
async function withServer(run, overrides = {}) {
  const calls = [];
  const server = createServer(createApp({ env: { ...env, ...overrides },
    fetchImpl: async (url, opts) => { calls.push({ url, opts }); return { ok: true }; },
    clock: () => 1000000 }));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const base = 'http://127.0.0.1:' + server.address().port;
  const token = { authorization: 'Bearer ' + env.JARVIS_INSTAGRAM_ADMIN_TOKEN };
  try { await run({ base, token, calls }); }
  finally { await new Promise((resolve, reject) => server.close(err => err ? reject(err) : resolve())); }
}
function signed(payload, secret = env.INSTAGRAM_APP_SECRET) {
  const raw = JSON.stringify(payload);
  return { raw, sig: 'sha256=' + createHmac('sha256', secret).update(raw).digest('hex') };
}
const event = { object: 'instagram', entry: [{ messaging: [{
  sender: { id: '123456' }, recipient: { id: env.INSTAGRAM_ACCOUNT_ID },
  message: { mid: 'mid.1', text: 'Привет!' },
}] }] };

test('health reports pilot mode without exposing secrets', () => withServer(async ({ base }) => {
  const res = await fetch(base + '/health'), payload = await res.text();
  assert.equal(res.status, 200);
  assert.match(payload, /human-approval-pilot/);
  assert.ok(!payload.includes(env.INSTAGRAM_ACCOUNT_TOKEN));
}));

test('GET webhook verifies mode and token', () => withServer(async ({ base }) => {
  const query = '?hub.mode=subscribe&hub.challenge=1234&hub.verify_token=';
  assert.equal((await fetch(base + '/webhook' + query + 'wrong')).status, 403);
  const ok = await fetch(base + '/webhook' + query + env.INSTAGRAM_WEBHOOK_VERIFY_TOKEN);
  assert.equal(await ok.text(), '1234');
  assert.equal(ok.status, 200);
}));

test('unsigned, tampered and malformed webhooks are rejected', () => withServer(async ({ base, token }) => {
  const { raw, sig } = signed(event);
  const call = (text, signature) => fetch(base + '/webhook', { method: 'POST',
    headers: { 'x-hub-signature-256': signature }, body: text });
  assert.equal((await call(raw, '')).status, 401);
  assert.equal((await call(raw + ' ', sig)).status, 401);
  const malformed = '{';
  const malformedSig = 'sha256=' + createHmac('sha256', env.INSTAGRAM_APP_SECRET).update(malformed).digest('hex');
  assert.equal((await call(malformed, malformedSig)).status, 400);
  assert.deepEqual((await (await fetch(base + '/admin/inbox', { headers: token })).json()).items, []);
}));

test('only signed inbound DMs are queued; replies require explicit owner approval', () => withServer(async ({ base, token, calls }) => {
  async function webhook(payload) {
    const { raw, sig } = signed(payload);
    return fetch(base + '/webhook', { method: 'POST', headers: { 'x-hub-signature-256': sig }, body: raw });
  }
  assert.equal((await webhook(event)).status, 200);
  assert.equal((await webhook(event)).status, 200); // duplicate must not create a new item
  const echoed = structuredClone(event);
  echoed.entry[0].messaging[0].message.mid = 'mid.2';
  echoed.entry[0].messaging[0].message.is_echo = true;
  await webhook(echoed);
  const wrongAccount = structuredClone(event);
  wrongAccount.entry[0].messaging[0].message.mid = 'mid.3';
  wrongAccount.entry[0].messaging[0].recipient.id = 'different-account';
  await webhook(wrongAccount);
  assert.equal((await fetch(base + '/admin/inbox')).status, 401);
  const inbox = await (await fetch(base + '/admin/inbox', { headers: token })).json();
  assert.equal(inbox.items.length, 1);
  assert.equal(inbox.items[0].text, 'Привет!');
  assert.ok(!('senderId' in inbox.items[0]));
  const send = (messageId, text) => fetch(base + '/admin/reply', {
    method: 'POST', headers: { ...token, 'content-type': 'application/json' },
    body: JSON.stringify({ messageId, text }),
  });
  assert.equal((await send('unknown', 'Спасибо!')).status, 409);
  assert.equal((await send('mid.1', 'Спасибо!')).status, 200);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].url, 'https://graph.instagram.com/v26.0/179123/messages');
  assert.deepEqual(JSON.parse(calls[0].opts.body), { recipient: { id: '123456' }, message: { text: 'Спасибо!' } });
  assert.equal((await send('mid.1', 'Ещё раз')).status, 409);
  await webhook(event);
  const after = await (await fetch(base + '/admin/inbox', { headers: token })).json();
  assert.equal(after.items.length, 0);
}));

test('fails closed when service is not configured', () => withServer(async ({ base }) => {
  assert.equal((await fetch(base + '/admin/inbox')).status, 503);
  assert.equal((await fetch(base + '/webhook?hub.mode=subscribe')).status, 503);
}, { INSTAGRAM_APP_SECRET: '', INSTAGRAM_WEBHOOK_VERIFY_TOKEN: '', INSTAGRAM_ACCOUNT_TOKEN: '' }));

test('admin UI ships a restrictive policy and loads static assets', () => withServer(async ({ base }) => {
  const html = await fetch(base + '/admin');
  assert.equal(html.status, 200);
  assert.match(html.headers.get('content-security-policy'), /frame-ancestors 'none'/);
  assert.match(await html.text(), /Instagram/);
  assert.equal((await fetch(base + '/admin.js')).status, 200);
  assert.equal((await fetch(base + '/admin.css')).status, 200);
}));
