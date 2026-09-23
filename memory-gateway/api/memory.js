import { AgentMemory } from '@redis-iris/agent-memory';
import { createHandler } from '../lib/handler.js';

let client;
function getClient() {
  if (!client) {
    const url = new URL(process.env.AGENT_MEMORY_BASE_URL);
    if (url.protocol !== 'https:' || url.username || url.password) throw new Error('Invalid Redis URL');
    client = new AgentMemory({
      serverURL: url.href,
      storeId: process.env.AGENT_MEMORY_STORE_ID,
      apiKey: process.env.AGENT_MEMORY_API_KEY,
      timeoutMs: 2000,
      retryConfig: { strategy: 'none' },
    });
  }
  return client;
}
export default createHandler({ getClient });
