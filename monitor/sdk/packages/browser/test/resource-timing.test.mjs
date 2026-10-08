import assert from 'node:assert/strict';
import { test } from 'node:test';
import { resourceTimingDetails } from '../dist/resource-timing.js';

function timing(overrides = {}) {
  return {
    name: 'https://app.example.test/assets/app.js',
    startTime: 10,
    domainLookupStart: 11,
    domainLookupEnd: 13,
    connectStart: 14,
    connectEnd: 19,
    secureConnectionStart: 15,
    requestStart: 22,
    responseStart: 40,
    responseEnd: 65,
    transferSize: 300,
    encodedBodySize: 200,
    decodedBodySize: 500,
    ...overrides
  };
}

test('resource timing includes phase durations, decoded bytes, and network status', () => {
  assert.deepEqual(resourceTimingDetails(timing()), {
    startTime: 10,
    dnsMs: 2,
    connectMs: 1,
    tlsMs: 4,
    requestMs: 18,
    responseMs: 25,
    transferSize: 300,
    encodedBodySize: 200,
    decodedBodySize: 500,
    cacheStatus: 'miss'
  });
});

test('resource timing only marks a zero-transfer response as a cache hit when sizes are visible', () => {
  assert.equal(resourceTimingDetails(timing({
    transferSize: 0,
    encodedBodySize: 200,
    decodedBodySize: 500
  })).cacheStatus, 'hit');

  assert.equal(resourceTimingDetails(timing({
    name: 'https://cdn.example.test/assets/app.js',
    domainLookupStart: 0,
    domainLookupEnd: 0,
    connectStart: 0,
    connectEnd: 0,
    requestStart: 0,
    responseStart: 0,
    responseEnd: 0,
    transferSize: 0,
    encodedBodySize: 0,
    decodedBodySize: 0
  })).cacheStatus, 'unknown');

  const incompleteTiming = resourceTimingDetails(timing({ requestStart: 0, responseStart: 40 }));
  assert.equal(incompleteTiming.requestMs, undefined);
});

test('resource timing stays unknown when service worker cache visibility is inconclusive', () => {
  assert.equal(resourceTimingDetails(timing({ deliveryType: 'serviceworker' })).cacheStatus, 'unknown');
});
