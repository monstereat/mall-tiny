import assert from 'node:assert/strict';
import { test } from 'node:test';
import { subscribeWebVitals } from '../dist/web-vitals.js';

function fakeApi() {
  const callbacks = new Map();
  const register = name => (callback, options) => {
    const entries = callbacks.get(name) ?? [];
    entries.push({ callback, options });
    callbacks.set(name, entries);
  };
  return {
    callbacks,
    api: {
      onCLS: register('CLS'),
      onFCP: register('FCP'),
      onINP: register('INP'),
      onLCP: register('LCP'),
      onTTFB: register('TTFB')
    }
  };
}

function metric(overrides = {}) {
  return {
    name: 'CLS',
    value: 0.4,
    delta: 0.2,
    id: 'metric-1',
    navigationType: 'navigate',
    navigationURL: 'https://app.example.test/#/route',
    ...overrides
  };
}

test('web vitals emit updates with stable metric IDs and per-ID monotonic update numbers', () => {
  const { api, callbacks } = fakeApi();
  const events = [];
  const client = { capture(...args) { events.push(args); return `event-${events.length}`; } };
  const host = {};
  const unsubscribe = subscribeWebVitals(host, client, value => value, true, api);

  const reporter = callbacks.get('CLS')[0];
  assert.equal(callbacks.get('CLS').length, 1);
  assert.equal(reporter.options.reportAllChanges, true);
  assert.equal(reporter.options.reportSoftNavs, true);
  reporter.callback(metric());
  reporter.callback(metric({ value: 0.6, delta: 0.2 }));
  reporter.callback(metric({ id: 'soft-metric', navigationType: 'soft-navigation' }));

  assert.equal(events.length, 3);
  assert.equal(events[0][0].data.metricId, 'metric-1');
  assert.equal(events[0][0].data.metricUpdate, 1);
  assert.equal(events[1][0].data.metricUpdate, 2);
  assert.equal(events[2][0].data.metricUpdate, 3);
  assert.equal(events[2][0].data.navigationType, 'soft-navigation');
  assert.equal(events[0][0].pageUrl, 'https://app.example.test/#/route');
  assert.equal(events[0][2], true);
  unsubscribe();
  reporter.callback(metric({ value: 0.7 }));
  assert.equal(events.length, 3);
});

test('web-vitals listener is installed once per host across repeated subscribe and destroy cycles', () => {
  const { api, callbacks } = fakeApi();
  const host = {};
  const firstEvents = [];
  const secondEvents = [];
  const first = subscribeWebVitals(host,
    { capture(...args) { firstEvents.push(args); return 'first'; } }, value => value, true, api);
  first();
  const second = subscribeWebVitals(host,
    { capture(...args) { secondEvents.push(args); return 'second'; } }, value => value, true, api);

  assert.equal(callbacks.get('CLS').length, 1);
  callbacks.get('CLS')[0].callback(metric());
  assert.equal(firstEvents.length, 0);
  assert.equal(secondEvents.length, 1);
  second();
});

test('synchronous buffered metrics are delivered to the first subscriber', () => {
  const events = [];
  const api = {
    onFCP(callback) { callback(metric({ name: 'FCP', id: 'buffered-fcp' })); },
    onTTFB() {},
    onCLS() {},
    onINP() {},
    onLCP() {}
  };
  const unsubscribe = subscribeWebVitals({},
    { capture(...args) { events.push(args); return 'buffered'; } }, value => value, true, api);

  assert.equal(events.length, 1);
  assert.equal(events[0][0].data.metric, 'FCP');
  assert.equal(events[0][0].data.metricUpdate, 1);
  unsubscribe();
});

test('reinitializing a subscriber replays the latest five core metrics without advancing their sequence', () => {
  const { api, callbacks } = fakeApi();
  const host = {};
  const firstEvents = [];
  const first = subscribeWebVitals(host,
    { capture(...args) { firstEvents.push(args); return 'first'; } }, value => value, true, api);
  const names = ['FCP', 'TTFB', 'CLS', 'INP', 'LCP'];
  const ids = new Map();

  for (const name of names) {
    const id = `first-${name}`;
    ids.set(name, id);
    const buffered = metric({ name, id, value: name === 'CLS' ? 0.25 : 250 });
    if (name === 'CLS') buffered.entries = new Array(1000).fill({ detail: 'not retained' });
    callbacks.get(name)[0].callback(buffered);
    if (name === 'CLS') buffered.value = 999;
  }
  assert.equal(firstEvents.length, 5);
  const originalSequences = new Map(firstEvents.map(([event]) => [event.data.metric, event.data.metricUpdate]));
  first();

  const replayEvents = [];
  const second = subscribeWebVitals(host,
    { capture(...args) { replayEvents.push(args); return 'second'; } }, value => value, true, api);
  assert.equal(replayEvents.length, 5);
  for (const [event] of replayEvents) {
    assert.equal(event.data.metricId, ids.get(event.data.metric));
    assert.equal(event.data.metricUpdate, originalSequences.get(event.data.metric));
    assert.notEqual(event.data.value, 999);
  }

  callbacks.get('CLS')[0].callback(metric({ name: 'CLS', id: ids.get('CLS'), value: 0.5 }));
  assert.equal(replayEvents.at(-1)[0].data.metricUpdate, 6);
  second();
});

test('global metric report sequence stays monotonic after more than 500 IDs and an old ID repeats', () => {
  const { api, callbacks } = fakeApi();
  const events = [];
  const unsubscribe = subscribeWebVitals({},
    { capture(...args) { events.push(args); return 'event'; } }, value => value, true, api);
  const report = callbacks.get('CLS')[0].callback;

  for (let index = 0; index < 500; index += 1) {
    report(metric({ id: `metric-${index}` }));
  }
  report(metric({ id: 'metric-499', value: 0.8 }));
  assert.equal(events.at(-1)[0].data.metricUpdate, 501);

  report(metric({ id: 'metric-500' }));
  report(metric({ id: 'metric-0' }));
  assert.equal(events.at(-1)[0].data.metricUpdate, 503);
  unsubscribe();
});

test('invalid metric values and disabled navigation timing are not sent', () => {
  const { api, callbacks } = fakeApi();
  const host = {};
  const events = [];
  const unsubscribe = subscribeWebVitals(host,
    { capture(...args) { events.push(args); return 'event'; } }, value => value, false, api);

  callbacks.get('CLS')[0].callback(metric({ value: Number.NaN }));
  callbacks.get('FCP')[0].callback(metric({ name: 'FCP', id: 'visible-fcp' }));
  callbacks.get('TTFB')[0].callback(metric({ name: 'TTFB', id: 'disabled-ttfb' }));
  assert.deepEqual(events.map(([event]) => event.data.metric), ['FCP']);
  unsubscribe();

  const replayEvents = [];
  const replay = subscribeWebVitals(host,
    { capture(...args) { replayEvents.push(args); return 'replay'; } }, value => value, false, api);
  assert.deepEqual(replayEvents.map(([event]) => event.data.metric), ['FCP']);
  assert.ok(replayEvents.every(([event]) => event.data.metric !== 'TTFB'));
  replay();
});
