import assert from 'node:assert/strict';
import { afterEach, test } from 'node:test';
import { init } from '../dist/index.js';

const descriptors = new Map();
const monitors = [];

function setGlobal(name, value) {
  if (!descriptors.has(name)) descriptors.set(name, Object.getOwnPropertyDescriptor(globalThis, name));
  Object.defineProperty(globalThis, name, { configurable: true, writable: true, value });
}

function setup(fetchHandler, tracePropagation = 'all') {
  const listeners = new Map();
  const location = {
    href: 'https://page-user:page-password@app.example.test/current?session=page-secret#page-fragment',
    origin: 'https://app.example.test'
  };
  const history = {
    pushState() {},
    replaceState() {}
  };
  const window = {
    location,
    history,
    innerWidth: 1024,
    innerHeight: 768,
    fetch: fetchHandler,
    addEventListener(name, handler) { listeners.set(name, handler); },
    removeEventListener(name) { listeners.delete(name); },
    setTimeout,
    clearTimeout
  };
  const document = {
    addEventListener() {},
    removeEventListener() {},
    elementFromPoint() { return { tagName: 'DIV' }; }
  };
  setGlobal('location', location);
  setGlobal('window', window);
  setGlobal('history', history);
  setGlobal('document', document);
  setGlobal('fetch', fetchHandler);
  const monitor = init({
    endpoint: 'https://app.example.test/monitor/ingest',
    projectId: 'project',
    ingestKey: 'key',
    flushInterval: 0,
    releaseHealth: false,
    captureErrors: false,
    capturePerformance: false,
    captureWhiteScreen: false,
    captureClicks: false,
    tracePropagation
  });
  monitors.push(monitor);
  return monitor;
}

afterEach(async () => {
  await Promise.all(monitors.splice(0).map(monitor => monitor.destroy()));
  for (const [name, descriptor] of descriptors) {
    if (descriptor) Object.defineProperty(globalThis, name, descriptor);
    else delete globalThis[name];
  }
  descriptors.clear();
});

test('fetch emits a successful span with a generated trace context and a sanitized URL', async () => {
  let outgoing;
  const monitor = setup(async (input, init) => {
    if (String(input).startsWith('https://app.example.test/monitor/ingest')) return new Response(null, { status: 200 });
    outgoing = { input, init };
    return new Response(null, { status: 200 });
  });

  await window.fetch('https://user:password@api.example.test/items?token=secret&keep=yes#private');
  await window.fetch('https://app.example.test/monitor/ingest/batch');

  const event = monitor.client.queue.find(item => item.eventType === 'SPAN');
  assert.ok(event);
  assert.equal(event.data.op, 'http.client');
  assert.equal(event.data.status, 'ok');
  assert.equal(event.data.statusCode, 200);
  assert.equal(event.timestamp, event.data.startTime + event.data.durationMs);
  assert.match(event.data.spanId, /^[0-9a-f]{16}$/);
  assert.equal(event.traceId, outgoing.init.headers.get('traceparent').split('-')[1]);
  assert.equal(outgoing.init.headers.get('traceparent').split('-')[2], event.data.spanId);
  assert.match(event.data.description, /token=%5Bredacted%5D/);
  assert.doesNotMatch(JSON.stringify(event.data), /secret|private|password|user:/);
  assert.doesNotMatch(event.pageUrl, /page-user|page-password|page-secret|page-fragment/);
  assert.ok(event.data.description.length <= 200);
  assert.equal(monitor.client.queue.filter(item => item.eventType === 'SPAN').length, 1);
});

test('fetch keeps 4xx spans ok, keeps 5xx error events, and emits one error span on network failure', async () => {
  let status = 404;
  const monitor = setup(async input => {
    if (String(input).startsWith('https://app.example.test/monitor/ingest')) return new Response(null, { status: 200 });
    if (status === 0) throw new TypeError('network failed');
    return new Response(null, { status });
  });

  await window.fetch('https://api.example.test/missing');
  let span = monitor.client.queue.find(item => item.eventType === 'SPAN');
  assert.equal(span.data.status, 'ok');
  assert.equal(span.data.statusCode, 404);

  status = 503;
  await window.fetch('https://api.example.test/fault');
  const queueAfter5xx = [...monitor.client.queue];
  span = queueAfter5xx.filter(item => item.eventType === 'SPAN').at(-1);
  assert.equal(span.data.status, 'error');
  assert.equal(span.data.statusCode, 503);
  assert.ok(queueAfter5xx.some(item => item.eventType === 'ERROR' && item.data.name === 'HttpError'));

  status = 0;
  await assert.rejects(window.fetch('https://api.example.test/offline'), /network failed/);
  const spans = monitor.client.queue.filter(item => item.eventType === 'SPAN');
  assert.equal(spans.length, 3);
  assert.equal(spans.at(-1).data.status, 'error');
  assert.equal(spans.at(-1).data.statusCode, undefined);
  assert.ok(monitor.client.queue.some(item => item.eventType === 'ERROR' && item.data.message === 'network failed'));
});

test('fetch derives a child span from an upstream traceparent and preserves Request body and headers', async () => {
  const upstreamTraceId = '1234567890abcdef1234567890abcdef';
  const upstreamSpanId = 'abcdef1234567890';
  let received;
  const monitor = setup(async (input, init) => {
    if (String(input).startsWith('https://app.example.test/monitor/ingest')) return new Response(null, { status: 200 });
    received = { request: input, init, body: await input.clone().text() };
    return new Response(null, { status: 200 });
  });
  const request = new Request('https://api.example.test/submit?password=pw', {
    method: 'POST',
    headers: { authorization: 'do-not-copy-to-span', traceparent: `00-${upstreamTraceId}-${upstreamSpanId}-01` },
    body: 'request-body-secret'
  });

  await window.fetch(request, { headers: { 'x-extra': 'preserved' } });

  const event = monitor.client.queue.find(item => item.eventType === 'SPAN');
  const outgoingParent = received.init.headers.get('traceparent');
  assert.equal(event.traceId, upstreamTraceId);
  assert.equal(event.data.parentSpanId, upstreamSpanId);
  assert.equal(outgoingParent.split('-')[1], upstreamTraceId);
  assert.equal(outgoingParent.split('-')[2], event.data.spanId);
  assert.notEqual(event.data.spanId, upstreamSpanId);
  assert.equal(received.request.headers.get('authorization'), 'do-not-copy-to-span');
  assert.equal(received.init.headers.get('x-extra'), 'preserved');
  assert.equal(received.body, 'request-body-secret');
  assert.doesNotMatch(JSON.stringify(event), /do-not-copy|request-body-secret|pw/);
  assert.match(event.data.description, /password=%5Bredacted%5D/);
});

test('cross-origin requests retain local trace IDs when automatic propagation is disabled', async () => {
  class MockXhr extends EventTarget {
    requestHeaders = new Map();
    status = 0;
    open(_method, url) { this.url = url; }
    setRequestHeader(name, value) {
      const key = name.toLowerCase();
      const previous = this.requestHeaders.get(key);
      this.requestHeaders.set(key, previous ? `${previous}, ${value}` : value);
    }
    getResponseHeader() { return null; }
    send() {}
    finish(status) {
      this.status = status;
      this.dispatchEvent(new Event('loadend'));
    }
  }
  setGlobal('XMLHttpRequest', MockXhr);
  let outgoingFetch;
  const monitor = setup(async (input, init) => {
    if (String(input).startsWith('https://app.example.test/monitor/ingest')) return new Response(null, { status: 200 });
    outgoingFetch = { input, init };
    return new Response(null, { status: 200 });
  }, 'same-origin');

  await window.fetch('https://outside.example.test/api');
  const xhr = new XMLHttpRequest();
  xhr.open('GET', 'https://outside.example.test/xhr');
  xhr.send();
  xhr.finish(200);

  const originalTraceparent = '00-11111111111111111111111111111111-2222222222222222-01';
  const inheritedXhr = new XMLHttpRequest();
  inheritedXhr.open('GET', 'https://outside.example.test/inherited');
  inheritedXhr.setRequestHeader('traceparent', originalTraceparent);
  inheritedXhr.send();
  inheritedXhr.finish(200);

  const spans = monitor.client.queue.filter(item => item.eventType === 'SPAN');
  assert.equal(spans.length, 3);
  for (const span of spans) assert.match(span.traceId, /^[0-9a-f]{32}$/);
  assert.equal(outgoingFetch.init.headers.has('traceparent'), false);
  assert.equal(xhr.requestHeaders.has('traceparent'), false);
  assert.equal(inheritedXhr.requestHeaders.get('traceparent'), originalTraceparent);
  assert.equal(spans[2].traceId, '11111111111111111111111111111111');
  assert.equal(spans[2].data.parentSpanId, '2222222222222222');
});

test('XHR emits one completed span and preserves inherited trace context while redacting its URL', async () => {
  const upstreamTraceId = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
  const upstreamSpanId = 'bbbbbbbbbbbbbbbb';
  class MockXhr extends EventTarget {
    requestHeaders = new Map();
    status = 0;
    open(_method, url) { this.url = url; }
    setRequestHeader(name, value) {
      const key = name.toLowerCase();
      const previous = this.requestHeaders.get(key);
      this.requestHeaders.set(key, previous ? `${previous}, ${value}` : value);
    }
    getResponseHeader() { return null; }
    send(body) { this.body = body; }
    finish(status) {
      this.status = status;
      this.dispatchEvent(new Event('loadend'));
    }
  }
  setGlobal('XMLHttpRequest', MockXhr);
  const monitor = setup(async input => {
    if (String(input).startsWith('https://app.example.test/monitor/ingest')) return new Response(null, { status: 200 });
    return new Response(null, { status: 200 });
  });
  const xhr = new XMLHttpRequest();
  xhr.open('GET', 'https://api.example.test/data?access_token=secret#fragment');
  xhr.setRequestHeader('traceparent', `00-${upstreamTraceId}-${upstreamSpanId}-01`);
  xhr.send();
  xhr.finish(200);

  const spans = monitor.client.queue.filter(item => item.eventType === 'SPAN');
  assert.equal(spans.length, 1);
  assert.equal(spans[0].traceId, upstreamTraceId);
  assert.equal(spans[0].data.parentSpanId, upstreamSpanId);
  assert.notEqual(spans[0].data.spanId, upstreamSpanId);
  assert.equal(xhr.requestHeaders.get('traceparent'), `00-${upstreamTraceId}-${spans[0].data.spanId}-01`);
  assert.match(spans[0].data.description, /access_token=%5Bredacted%5D/);
  assert.doesNotMatch(JSON.stringify(spans[0]), /secret|fragment/);
  xhr.finish(200);
  assert.equal(monitor.client.queue.filter(item => item.eventType === 'SPAN').length, 1);

  const failedXhr = new XMLHttpRequest();
  failedXhr.open('GET', 'https://api.example.test/offline');
  failedXhr.send();
  failedXhr.finish(0);
  const spansAfterFailure = monitor.client.queue.filter(item => item.eventType === 'SPAN');
  assert.equal(spansAfterFailure.length, 2);
  assert.equal(spansAfterFailure[1].data.status, 'error');
  assert.equal(spansAfterFailure[1].data.statusCode, undefined);
});
