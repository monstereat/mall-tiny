import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { MonitorClient } from '../dist/index.js';

const clients = [];
const originalFetch = globalThis.fetch;
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
const originalRandom = Math.random;
const batches = [];
function client(options = {}) {
  globalThis.fetch = async (_url, init) => { batches.push(JSON.parse(init.body)); return { ok: true }; };
  const value = new MonitorClient({ endpoint: 'https://monitor.example/ingest', projectId: 'project',
    ingestKey: 'synthetic-key', flushInterval: 0, batchSize: 100, persistQueue: false,
    releaseHealth: false, ...options });
  clients.push(value);
  return value;
}
afterEach(async () => {
  await Promise.all(clients.splice(0).map(value => value.close()));
  batches.length = 0;
  globalThis.fetch = originalFetch;
  Math.random = originalRandom;
  if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage);
  else delete globalThis.localStorage;
});

test('business events use independent sampling and filter credentials before queuing', async () => {
  const value = client({ sampleRate: 0 });
  value.identify('user-1');
  assert.ok(value.track('register_success', { source: 'campaign', nested: { token: 'private' } },
    { pageUrl: 'https://user:pass@app.example/register?token=secret#fragment' }));
  await value.flush();
  const event = batches[0].events[0];
  assert.equal(event.eventType, 'BEHAVIOR');
  assert.equal(event.userId, 'user-1');
  assert.equal(event.pageUrl, 'https://app.example/register');
  assert.deepEqual(event.data, { category: 'business', event: 'register_success',
    properties: { source: 'campaign', nested: { token: '[Filtered]' } }, analyticsSampleRate: 1 });
});

test('page and visible dwell correlate without resampling and preserve the original identity', async () => {
  const value = client({ sampleRate: 0 });
  value.identify('page-user');
  const id = value.page({ url: 'https://app.example/#/register?token=secret', name: 'register' });
  assert.ok(id);
  value.identify('new-user');
  assert.ok(value.capturePageDwell(id, 'https://app.example/#/register', 1500));
  assert.equal(value.capturePageDwell('unknown', 'https://app.example/', 10), null);
  await value.flush();
  const [page, dwell] = batches[0].events;
  assert.equal(page.data.pageViewId, id);
  assert.equal(dwell.data.pageViewId, id);
  assert.equal(dwell.data.durationMs, 1500);
  assert.equal(dwell.pageUrl, 'https://app.example/#/register');
  assert.equal(dwell.userId, 'page-user');
  assert.equal(dwell.sessionId, page.sessionId);
});

test('analytics sampling can be disabled independently', () => {
  const value = client({ analyticsSampleRate: 0 });
  assert.equal(value.track('click'), null);
  assert.equal(value.page(), null);
  assert.equal(value.capturePageDwell('missing', '/', 10), null);
  assert.equal(value.queue.length, 0);
});

test('zero and invalid normal sampling cannot fail open even when the random value is zero', () => {
  Math.random = () => 0;
  for (const sampleRate of [0, NaN, Infinity]) {
    const value = client({ sampleRate });
    assert.equal(value.capturePerformance('Test', 1), null);
    assert.equal(value.queue.length, 0);
  }
});

test('restored offline events obey the current beforeSend and storage no longer retains unfiltered data', () => {
  const stored = { eventId: 'old-event', projectId: 'project', eventType: 'ERROR', timestamp: Date.now(),
    sessionId: 'old-session', pageUrl: 'https://app.example/', sdkVersion: 'old', device: {},
    environment: 'production', data: { message: 'private-offline-content' } };
  const storage = new Map([['__observe_queue__:project', JSON.stringify([stored])]]);
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
    getItem(key) { return storage.get(key) ?? null; },
    setItem(key, value) { storage.set(key, value); },
    removeItem(key) { storage.delete(key); }
  } });
  const value = client({ persistQueue: true,
    beforeSend: event => ({ ...event, data: { ...event.data, message: 'filtered' } }) });
  assert.equal(value.queue[0].data.message, 'filtered');
  assert.doesNotMatch(storage.get('__observe_queue__:project'), /private-offline-content/);
  const dropped = client({ persistQueue: true, beforeSend: () => null });
  assert.equal(dropped.queue.length, 0);
  assert.equal(storage.has('__observe_queue__:project'), false);
});

test('invalid event names, values and oversized properties do not affect the application', () => {
  const value = client();
  assert.equal(value.track('bad event name'), null);
  assert.equal(value.track('click', { value: Infinity }), null);
  assert.equal(value.track('click', Object.fromEntries(Array.from({ length: 33 }, (_, i) => [`k${i}`, i]))), null);
  assert.equal(value.track('click', { nested: { a: { b: { c: 'deep' } } } }), null);
  assert.equal(value.track('click', Object.fromEntries(Array.from({ length: 32 }, (_, i) => [`k${i}`, 'x'.repeat(1000)]))), null);
  assert.equal(value.queue.length, 0);
});

test('beforeSend rewrites events before persistence and errors notify with rewritten data', () => {
  const received = [];
  const value = client({ beforeSend: event => ({ ...event, data: { ...event.data, message: 'filtered' } }) });
  value.onError(event => received.push(event));
  value.captureException(new Error('private'));
  assert.equal(value.queue[0].data.message, 'filtered');
  assert.equal(received[0].data.message, 'filtered');
});

test('beforeSend drop, failure and identity changes neither queue nor trigger replay listeners', () => {
  for (const beforeSend of [() => null, () => { throw new Error('synthetic'); },
    event => ({ ...event, projectId: 'other-project' })]) {
    const value = client({ beforeSend });
    let notified = false;
    value.onError(() => { notified = true; });
    assert.equal(value.captureException(new Error('private')), null);
    assert.equal(value.page(), null);
    assert.equal(value.queue.length, 0);
    assert.equal(notified, false);
  }
});

test('visible dwell accepts only the current page and bounded finite duration', async () => {
  const value = client();
  const old = value.page({ url: 'https://app.example/old' });
  value.page({ url: 'https://app.example/new' });
  assert.equal(value.capturePageDwell(old, 'https://app.example/old', 10), null);
  const id = value.page({ url: 'https://app.example/new' });
  for (const duration of [-1, Infinity, NaN, 86_400_001]) {
    assert.equal(value.capturePageDwell(id, 'https://app.example/new', duration), null);
  }
  await value.close();
  assert.equal(value.track('after_close'), null);
  assert.equal(value.page(), null);
});
