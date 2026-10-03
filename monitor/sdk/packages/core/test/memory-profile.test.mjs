import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { MonitorClient } from '../dist/index.js';

const originalDescriptors = new Map();
const clients = [];

function replaceGlobal(name, value) {
  if (!originalDescriptors.has(name)) {
    originalDescriptors.set(name, Object.getOwnPropertyDescriptor(globalThis, name));
  }
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
}

function makeClient(options = {}) {
  const client = new MonitorClient({
    endpoint: 'https://monitor.example/api/ingest',
    projectId: 'project-key',
    ingestKey: 'ingest-key',
    flushInterval: 0,
    persistQueue: false,
    releaseHealth: false,
    ...options
  });
  clients.push(client);
  return client;
}

afterEach(async () => {
  await Promise.all(clients.splice(0).map(client => client.close()));
  for (const [name, descriptor] of originalDescriptors) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor);
    else delete globalThis[name];
  }
  originalDescriptors.clear();
});

test('release health session markers bypass event sampling and track user sessions', async () => {
  const batches = [];
  replaceGlobal('fetch', async (_url, init) => {
    batches.push(JSON.parse(init.body));
    return { ok: true };
  });

  const client = makeClient({ releaseHealth: true, sampleRate: 0, batchSize: 1 });
  await new Promise(resolve => setTimeout(resolve, 0));
  const anonymousSession = client.getSessionId();
  client.setUser('release-health-user');
  const authenticatedSession = client.getSessionId();
  await client.close();

  const events = batches.flatMap(batch => batch.events);
  const starts = events.filter(event => event.data.category === 'session' && event.data.action === 'start');
  const ends = events.filter(event => event.data.category === 'session' && event.data.action === 'end');
  assert.equal(starts.length, 2);
  assert.equal(ends.length, 2);
  assert.equal(starts[0].sessionId, anonymousSession);
  assert.equal(starts[1].sessionId, authenticatedSession);
  assert.notEqual(anonymousSession, authenticatedSession);
  assert.equal(starts[1].userId, 'release-health-user');
});

test('memory profiling stays disabled by default', async () => {
  let measurements = 0;
  replaceGlobal('isSecureContext', true);
  replaceGlobal('crossOriginIsolated', true);
  replaceGlobal('performance', {
    measureUserAgentSpecificMemory: async () => { measurements += 1; return { bytes: 1024 }; }
  });

  const client = makeClient();
  await client.collectMemoryProfile();

  assert.equal(client.memoryProfileEnabled, false);
  assert.equal(measurements, 0);
  assert.equal(client.queue.length, 0);
});

test('memory profiling skips pages without cross-origin isolation', async () => {
  let measurements = 0;
  replaceGlobal('isSecureContext', true);
  replaceGlobal('crossOriginIsolated', false);
  replaceGlobal('performance', {
    measureUserAgentSpecificMemory: async () => { measurements += 1; return { bytes: 1024 }; }
  });

  const client = makeClient({ profileMemorySampleRate: 1 });
  await client.collectMemoryProfile();

  assert.equal(client.memoryProfileEnabled, false);
  assert.equal(measurements, 0);
  assert.equal(client.queue.length, 0);
});

test('memory profiling skips insecure contexts', async () => {
  let measurements = 0;
  replaceGlobal('isSecureContext', false);
  replaceGlobal('crossOriginIsolated', true);
  replaceGlobal('performance', {
    measureUserAgentSpecificMemory: async () => { measurements += 1; return { bytes: 1024 }; }
  });

  const client = makeClient({ profileMemorySampleRate: 1 });
  await client.collectMemoryProfile();

  assert.equal(client.memoryProfileEnabled, false);
  assert.equal(measurements, 0);
  assert.equal(client.queue.length, 0);
});

test('opted-in memory measurements use the existing profile ingest envelope', async () => {
  let receiver;
  const performanceApi = {
    measureUserAgentSpecificMemory: async function () {
      assert.equal(this, performanceApi);
      return { bytes: 42_000_000 };
    }
  };
  replaceGlobal('isSecureContext', true);
  replaceGlobal('crossOriginIsolated', true);
  replaceGlobal('performance', performanceApi);
  replaceGlobal('fetch', async (_url, init) => {
    receiver = JSON.parse(init.body);
    return { ok: true };
  });

  const client = makeClient({ profileMemorySampleRate: 1 });
  await client.collectMemoryProfile();
  await client.close();

  const [event] = receiver.events;
  assert.equal(event.eventType, 'PROFILE');
  assert.equal(event.data.format, 'collapsed');
  assert.equal(event.data.name, 'JavaScript Memory');
  assert.equal(event.data.unit, 'bytes');
  assert.deepEqual(event.data.samples, [{ stack: ['JavaScript memory (estimated)'], value: 42_000_000 }]);
});

test('invalid memory measurements are discarded', async () => {
  replaceGlobal('isSecureContext', true);
  replaceGlobal('crossOriginIsolated', true);
  replaceGlobal('performance', {
    measureUserAgentSpecificMemory: async () => ({ bytes: Number.NaN })
  });

  const client = makeClient({ profileMemorySampleRate: 1 });
  await client.collectMemoryProfile();

  assert.equal(client.queue.length, 0);
});
