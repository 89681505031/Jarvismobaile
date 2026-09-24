import assert from 'node:assert/strict';
import test from 'node:test';
import { AgentMemory } from '@redis-iris/agent-memory';
import { HTTPClient } from '@redis-iris/agent-memory/lib/http.js';
import { createHandler } from '../lib/handler.js';

const env = { AGENT_MEMORY_BASE_URL: 'https://memory.example', AGENT_MEMORY_STORE_ID: 'store-1',
  AGENT_MEMORY_API_KEY: 'test-store-key', JARVIS_MEMORY_TOKEN: 'a'.repeat(48), JARVIS_MEMORY_OWNER_ID: 'alex' };
const body = { action: 'recall', sessionId: 'session-1', text: 'Что я люблю?', ownerId: 'someone-else' };
async function invoke(client, overrides = {}, config = env) {
  const response = { headers: {}, setHeader(k, v) { this.headers[k] = v; },
    status(code) { this.code = code; return this; }, json(value) { this.body = value; return this; } };
  await createHandler({ getClient: () => client, env: config })({
    method: 'POST', headers: { authorization: `Bearer ${env.JARVIS_MEMORY_TOKEN}` }, body, ...overrides }, response);
  return response;
}
test('unconfigured service reports 503; invalid token reports 401 without calling SDK', async () => {
  assert.equal((await invoke(null, {}, {})).code, 503);
  assert.equal((await invoke(null, { headers: {} })).code, 401);
  assert.equal((await invoke(null, { headers: { authorization: env.JARVIS_MEMORY_TOKEN } })).code, 401);
});
test('method and payload validation prevent upstream calls', async () => {
  assert.equal((await invoke(null, { method: 'GET' })).code, 405);
  for (const change of [{ action: 'delete' }, { text: '' }, { text: 'x'.repeat(4001) },
    { sessionId: {} }, { action: 'record', answer: 'ok' }]) {
    assert.equal((await invoke(null, { body: { ...body, ...change } })).code, 400);
  }
});
test('real SDK sends server-owned filter and parses Redis items response', async () => {
  let query;
  const client = new AgentMemory({ serverURL: env.AGENT_MEMORY_BASE_URL, storeId: env.AGENT_MEMORY_STORE_ID,
    apiKey: env.AGENT_MEMORY_API_KEY, httpClient: new HTTPClient({ fetcher: async request => {
      query = await request.json();
      return new Response(JSON.stringify({ items: [{ id: 'memory-1', ownerId: 'alex', text: 'Любит космос',
        createdAt: '2026-09-23T00:00:00Z', updatedAt: '2026-09-23T00:00:00Z' }] }),
        { status: 200, headers: { 'Content-Type': 'application/json' } });
    } }) });
  const result = await invoke(client);
  assert.equal(result.code, 200);
  assert.deepEqual(query.filter.ownerId, { eq: 'alex' });
  assert.deepEqual(result.body.memories, ['Любит космос']);
});
test('records have explicit ownership and stable IDs; sessions keep event order', async () => {
  const records = [], events = [];
  const client = { bulkCreateLongTermMemories: async request => {
    records.push(request.memories[0]); return { created: [request.memories[0].id] };
  }, addSessionEvent: async event => { events.push(event); return {}; } };
  const request = { body: { ...body, action: 'record', turnId: 'turn-1', answer: 'Ответ' } };
  assert.equal((await invoke(client, request)).code, 200);
  assert.equal((await invoke(client, request)).code, 200);
  assert.equal(records[0].ownerId, 'alex');
  assert.equal(records[0].id, records[1].id);
  assert.deepEqual(events.slice(0, 2).map(x => x.role), ['USER', 'ASSISTANT']);
  assert.equal(events[0].sessionId, events[1].sessionId);
  assert.equal(events[0].actorId, 'alex');
  assert.ok(events[0].createdAt instanceof Date);
});
test('partial bulk failure and SDK errors return 502 without leaking request data', async () => {
  let result = await invoke({ searchLongTermMemory: async () => { throw new Error('SECRET'); } });
  assert.equal(result.code, 502);
  assert.ok(!JSON.stringify(result.body).includes('SECRET'));
  result = await invoke({ bulkCreateLongTermMemories: async () => ({ errors: [{ message: 'failed' }] }) },
    { body: { ...body, action: 'record', turnId: 'turn-1', answer: 'Ответ' } });
  assert.equal(result.code, 502);
});
