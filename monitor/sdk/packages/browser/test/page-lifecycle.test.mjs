import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { init } from '../dist/index.js';

const descriptors = new Map();
const monitors = [];

function setGlobal(name, value) {
  if (!descriptors.has(name)) descriptors.set(name, Object.getOwnPropertyDescriptor(globalThis, name));
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
}

function listenerSurface() {
  const listeners = new Map();
  return {
    addEventListener(name, handler) {
      const handlers = listeners.get(name) ?? new Set();
      handlers.add(handler);
      listeners.set(name, handlers);
    },
    removeEventListener(name, handler) {
      listeners.get(name)?.delete(handler);
    },
    dispatch(name, event = {}) {
      for (const handler of listeners.get(name) ?? []) handler(event);
    },
    listenerCount(name) { return listeners.get(name)?.size ?? 0; }
  };
}

function setup(beforeSend = event => event, analyticsSampleRate = 1, browserOptions = {}) {
  const captured = [];
  const clock = { value: 0 };
  const timers = { now: 0, nextId: 1, entries: new Map() };
  const setTimeout = (callback, delay = 0) => {
    const id = timers.nextId++;
    timers.entries.set(id, { callback, due: timers.now + delay });
    return id;
  };
  const clearTimeout = id => timers.entries.delete(id);
  const advanceTimers = duration => {
    const end = timers.now + duration;
    while (true) {
      const next = [...timers.entries.entries()]
        .filter(([, timer]) => timer.due <= end)
        .sort((left, right) => left[1].due - right[1].due)[0];
      if (!next) break;
      const [id, timer] = next;
      timers.entries.delete(id);
      timers.now = timer.due;
      timer.callback();
    }
    timers.now = end;
  };
  const location = {
    href: 'https://app.example.test/start?campaign=first#private-fragment',
    origin: 'https://app.example.test'
  };
  const history = {
    pushState(_state, _title, url) {
      if (url != null) location.href = new URL(url, location.href).toString();
    },
    replaceState(_state, _title, url) {
      if (url != null) location.href = new URL(url, location.href).toString();
    }
  };
  const window = Object.assign(listenerSurface(), {
    location,
    history,
    innerWidth: 1024,
    innerHeight: 768,
    fetch: async () => ({ ok: true }),
    setTimeout,
    clearTimeout
  });
  const appRoot = {
    tagName: 'DIV',
    parentElement: null,
    childNodes: [],
    matches(selector) { return selector === '#app'; }
  };
  const document = Object.assign(listenerSurface(), {
    referrer: 'https://app.example.test/previous?token=private',
    visibilityState: 'visible',
    querySelectorAll(selector) { return selector === '#app' ? [appRoot] : []; },
    elementFromPoint() { return appRoot; }
  });
  setGlobal('location', location);
  setGlobal('history', history);
  setGlobal('window', window);
  setGlobal('document', document);
  setGlobal('fetch', window.fetch);
  setGlobal('performance', { now: () => clock.value });

  const monitor = init({
    endpoint: 'https://app.example.test/monitor/ingest',
    projectId: 'synthetic-project',
    ingestKey: 'synthetic-key',
    releaseHealth: false,
    analyticsSampleRate,
    flushInterval: 0,
    batchSize: 100,
    captureErrors: false,
    captureFetch: false,
    captureXhr: false,
    captureClicks: false,
    capturePerformance: false,
    captureWhiteScreen: false,
    capturePageDwell: true,
    ...browserOptions,
    beforeSend(event) {
      captured.push(event);
      return beforeSend(event);
    }
  });
  monitors.push(monitor);
  return { monitor, captured, clock, document, history, location, window, timers, advanceTimers };
}

afterEach(async () => {
  await Promise.all(monitors.splice(0).map(monitor => monitor.destroy()));
  for (const [name, descriptor] of descriptors) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor);
    else delete globalThis[name];
  }
  descriptors.clear();
});

test('page views deduplicate canonical routes and dwell counts visible time only', async () => {
  const state = setup();
  const { monitor, captured, clock, document, history, location, window } = state;
  const duplicateInit = init({ endpoint: 'https://app.example.test/monitor/ingest',
    projectId: 'synthetic-project', ingestKey: 'synthetic-key' });
  assert.equal(duplicateInit, monitor);

  const pageViews = () => captured.filter(event => event.data.event === 'page_view');
  const dwellEvents = () => captured.filter(event => event.data.event === 'page_dwell');
  assert.equal(pageViews().length, 1);
  assert.equal(pageViews()[0].pageUrl, 'https://app.example.test/start');
  assert.doesNotMatch(pageViews()[0].data.referrer, /token|private/);

  clock.value = 1500;
  document.visibilityState = 'hidden';
  document.dispatch('visibilitychange');
  clock.value = 6000;
  document.visibilityState = 'visible';
  document.dispatch('visibilitychange');

  clock.value = 8000;
  history.pushState({}, '', '/next?campaign=one');
  assert.equal(pageViews().length, 2);
  assert.equal(pageViews()[1].pageUrl, 'https://app.example.test/next');

  clock.value = 9000;
  history.replaceState({}, '', '/next?campaign=two');
  assert.equal(pageViews().length, 2);

  clock.value = 10000;
  location.href = 'https://app.example.test/next?campaign=two#/products?token=secret';
  window.dispatch('hashchange');
  window.dispatch('hashchange');
  assert.equal(pageViews().length, 3);
  assert.equal(pageViews()[2].pageUrl, 'https://app.example.test/next#/products');
  assert.doesNotMatch(pageViews()[2].pageUrl, /secret|campaign/);

  clock.value = 12500;
  await monitor.destroy();
  assert.deepEqual(dwellEvents().map(event => event.data.durationMs), [1500, 2000, 2000, 2500]);
  const pageIds = new Set(pageViews().map(event => event.data.pageViewId));
  assert.ok(dwellEvents().every(event => pageIds.has(event.data.pageViewId)));
  assert.equal(window.listenerCount('hashchange'), 0);
  assert.equal(document.listenerCount('visibilitychange'), 0);
});

test('a page view rejected by sampling or beforeSend cannot produce dwell events', async () => {
  const rejected = setup(event => event.data.event === 'page_view' ? null : event);
  rejected.clock.value = 5000;
  await rejected.monitor.destroy();
  assert.equal(rejected.captured.filter(event => event.data.event === 'page_view').length, 1);
  assert.equal(rejected.captured.filter(event => event.data.event === 'page_dwell').length, 0);

  const sampledOut = setup(event => event, 0);
  sampledOut.clock.value = 5000;
  await sampledOut.monitor.destroy();
  assert.equal(sampledOut.captured.filter(event => event.data.event === 'page_view').length, 0);
  assert.equal(sampledOut.captured.filter(event => event.data.event === 'page_dwell').length, 0);
});

test('page view and dwell collection stay opt-in by default', async () => {
  const state = setup(undefined, 1, { capturePageDwell: false });
  state.clock.value = 5000;
  await state.monitor.destroy();
  assert.equal(state.captured.filter(event => event.data.event === 'page_view').length, 0);
  assert.equal(state.captured.filter(event => event.data.event === 'page_dwell').length, 0);
});

test('reinitializing another project cannot silently reuse the current project client', () => {
  setup();
  assert.throws(() => init({ endpoint: 'https://monitor.example/ingest', projectId: 'different-project',
    ingestKey: 'other-key' }), /Destroy the active monitor/);
});

test('white-screen checks reconfirm after SPA navigation and stop after destroy', async () => {
  const state = setup(undefined, 1, {
    captureWhiteScreen: true,
    whiteScreenDelay: 10,
    whiteScreenConfirmations: 2
  });
  state.advanceTimers(10);
  state.history.pushState({}, '', '/new-route');
  state.advanceTimers(2010);

  const errors = state.captured.filter(event => event.data.name === 'WhiteScreenError');
  assert.equal(errors.length, 1);
  assert.equal(errors[0].pageUrl, 'https://app.example.test/new-route');
  assert.equal(errors[0].data.confirmations, 2);

  await state.monitor.destroy();
  assert.equal(state.timers.entries.size, 0);
  state.advanceTimers(5000);
  assert.equal(state.captured.filter(event => event.data.name === 'WhiteScreenError').length, 1);
});
