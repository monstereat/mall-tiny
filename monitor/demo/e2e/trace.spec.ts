import { expect, test } from '@playwright/test';

const apiUrl = 'http://localhost:8080/admin/info';
const batchUrl = 'http://localhost:8080/api/v1/envelope/batch';
const jaegerUrl = 'http://localhost:16686';
let browserTraceDiagnostics: Record<string, unknown> = {};

test.afterEach(async ({}, testInfo) => {
  if (testInfo.title.startsWith('browser API probe') && testInfo.status !== testInfo.expectedStatus) {
    const body = JSON.stringify(browserTraceDiagnostics, null, 2);
    console.error(`Browser trace E2E diagnostics:\n${body}`);
    await testInfo.attach('browser-trace-e2e-diagnostics.json', {
      body,
      contentType: 'application/json'
    });
  }
});

function traceId(traceparent: string): string {
  const match = traceparent.match(/^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/i);
  expect(match, `valid W3C traceparent: ${traceparent}`).not.toBeNull();
  return match![1].toLowerCase();
}

test('browser API probe, SDK batch, Kafka producer and consumer share one trace', async ({ page, request }) => {
  browserTraceDiagnostics = { step: 'wait-for-server-health' };
  await expect.poll(async () => {
    const health = await request.get('http://localhost:8081/actuator/health');
    browserTraceDiagnostics = { step: 'wait-for-server-health', status: health.status() };
    return health.ok();
  }, { timeout: 120_000, intervals: [1000, 2000, 5000] }).toBe(true);

  browserTraceDiagnostics = { step: 'open-demo' };
  await page.goto('/');

  const apiResponsePromise = page.waitForResponse(response => response.url() === apiUrl);
  const behaviorBatchRequest = page.waitForRequest(candidate => {
    if (candidate.url() !== batchUrl || candidate.method() !== 'POST') return false;
    try {
      const body = candidate.postDataJSON() as { events?: Array<Record<string, unknown>> };
      return body.events?.some(event => {
        const data = event.data as Record<string, unknown> | undefined;
        return event.eventType === 'BEHAVIOR'
          && data?.category === 'api'
          && String(data.url).includes('/admin/info');
      }) ?? false;
    } catch {
      return false;
    }
  });
  browserTraceDiagnostics = { step: 'click-api-probe' };
  await page.getByRole('button', { name: /API Trace Probe/ }).click();
  const apiResponse = await apiResponsePromise;
  browserTraceDiagnostics = { step: 'check-api-response', status: apiResponse.status(), responseHeaders: await apiResponse.allHeaders(), requestHeaders: await apiResponse.request().allHeaders() };
  expect(apiResponse.ok()).toBeTruthy();

  const apiRequestTraceparent = (await apiResponse.request().allHeaders()).traceparent;
  const responseTraceparent = (await apiResponse.allHeaders()).traceparent;
  expect(apiRequestTraceparent).toBeTruthy();
  expect(responseTraceparent).toBeTruthy();
  expect(traceId(responseTraceparent!)).toBe(traceId(apiRequestTraceparent!));

  const batchRequest = await behaviorBatchRequest;
  const batchHeaders = await batchRequest.allHeaders();
  const batchTraceparent = batchHeaders.traceparent;
  browserTraceDiagnostics = { ...browserTraceDiagnostics, step: 'check-behavior-batch', batchHeaders, apiRequestTraceparent, responseTraceparent };
  expect(batchTraceparent).toBe(responseTraceparent);

  const batchBody = batchRequest.postDataJSON() as { events: Array<Record<string, unknown>> };
  const behaviorEvent = batchBody.events.find(event => {
    const data = event.data as Record<string, unknown> | undefined;
    return event.eventType === 'BEHAVIOR'
      && data?.category === 'api'
      && String(data.url).includes('/admin/info');
  });
  browserTraceDiagnostics = { ...browserTraceDiagnostics, behaviorEvent, batchEventCount: batchBody.events.length };
  expect(behaviorEvent).toBeTruthy();
  expect(String(behaviorEvent!.traceId).toLowerCase()).toBe(traceId(apiRequestTraceparent!));
  expect(traceId(batchTraceparent!)).toBe(traceId(apiRequestTraceparent!));

  const expectedTraceId = traceId(apiRequestTraceparent!);
  const responseParentSpanId = responseTraceparent!.split('-')[2].toLowerCase();
  let traceDiagnostics: Record<string, unknown> = { traceId: expectedTraceId };
  browserTraceDiagnostics = { ...browserTraceDiagnostics, step: 'wait-for-jaeger-trace', traceId: expectedTraceId };
  try {
    await expect.poll(async () => {
      const traceResponse = await request.get(`${jaegerUrl}/api/traces/${expectedTraceId}`);
      traceDiagnostics = { traceId: expectedTraceId, jaegerStatus: traceResponse.status() };
      browserTraceDiagnostics = { ...browserTraceDiagnostics, ...traceDiagnostics };
      if (!traceResponse.ok()) return false;
      const payload = await traceResponse.json() as {
        data?: Array<{
          traceID: string;
          spans: Array<{
            spanID: string;
            operationName: string;
            references?: Array<{ refType: string; spanID: string }>;
          }>;
        }>;
      };
      const trace = payload.data?.find(item => item.traceID.toLowerCase() === expectedTraceId);
      if (!trace) {
        traceDiagnostics = { traceId: expectedTraceId, jaegerStatus: traceResponse.status(), returnedTraceIds: payload.data?.map(item => item.traceID) ?? [] };
        browserTraceDiagnostics = { ...browserTraceDiagnostics, ...traceDiagnostics };
        return false;
      }
      const spans = trace.spans;
      const spanById = new Map(spans.map(span => [span.spanID.toLowerCase(), span]));
      const hasAncestor = (span: typeof spans[number], ancestorSpanId: string) => {
        let parentSpanId = span.references?.find(reference => reference.refType === 'CHILD_OF')?.spanID;
        while (parentSpanId) {
          if (parentSpanId.toLowerCase() === ancestorSpanId.toLowerCase()) return true;
          parentSpanId = spanById.get(parentSpanId.toLowerCase())?.references
            ?.find(reference => reference.refType === 'CHILD_OF')?.spanID;
        }
        return false;
      };
      const apiProbe = spans.find(span => span.operationName === 'http get /admin/info');
      const batchIngest = spans.find(span => span.operationName === 'http post /api/v1/envelope/batch'
        && span.references?.some(reference => reference.refType === 'CHILD_OF'
          && reference.spanID.toLowerCase() === responseParentSpanId));
      const producer = batchIngest && spans.find(span => span.operationName === 'monitor-behavior-v1 send'
        && hasAncestor(span, batchIngest.spanID));
      const consumer = producer && spans.find(span => span.operationName === 'monitor.kafka.consume'
        && span.references?.some(reference => reference.refType === 'CHILD_OF'
          && reference.spanID.toLowerCase() === producer.spanID.toLowerCase()));
      traceDiagnostics = {
        traceId: expectedTraceId,
        jaegerStatus: traceResponse.status(),
        expectedParentSpanId: responseParentSpanId,
        spans: spans.map(span => ({
          operationName: span.operationName,
          spanId: span.spanID,
          parents: span.references?.filter(reference => reference.refType === 'CHILD_OF').map(reference => reference.spanID) ?? []
        })),
        matched: { apiProbe: Boolean(apiProbe), batchIngest: Boolean(batchIngest), producer: Boolean(producer), consumer: Boolean(consumer) }
      };
      browserTraceDiagnostics = { ...browserTraceDiagnostics, ...traceDiagnostics };
      return Boolean(apiProbe && batchIngest && producer && consumer);
    }, { timeout: 60_000, intervals: [500, 1000, 2000] }).toBe(true);
  } catch (error) {
    browserTraceDiagnostics = { ...browserTraceDiagnostics, failure: String(error) };
    await test.info().attach('jaeger-trace-diagnostics.json', {
      body: JSON.stringify(traceDiagnostics, null, 2),
      contentType: 'application/json'
    });
    throw error;
  }
});

test('opted-in unsampled session uploads pre-error Replay context on error', async ({ page }) => {
  const batches: Array<Array<Record<string, unknown>>> = [];
  page.on('request', request => {
    if (request.url() !== batchUrl || request.method() !== 'POST') return;
    try {
      const body = request.postDataJSON() as { events?: Array<Record<string, unknown>> };
      if (body.events) batches.push(body.events);
    } catch {
      // Ignore requests without a JSON batch body.
    }
  });

  await page.goto('/?replayErrorBuffer=1&replaySampleRate=0');
  await page.waitForTimeout(2000);
  const errorTime = await page.evaluate(() => Date.now());
  await page.getByRole('button', { name: 'Error Replay Probe' }).click();

  await expect.poll(() => batches.flat().some(event => {
    const data = event.data as Record<string, unknown> | undefined;
    return event.eventType === 'ERROR' && data?.message === 'demo replay error-buffer probe';
  }), { timeout: 20_000, intervals: [500, 1000, 2000] }).toBe(true);

  await expect.poll(() => batches.flat().some(event => event.eventType === 'REPLAY'), {
    timeout: 20_000,
    intervals: [500, 1000, 2000]
  }).toBe(true);
  const replay = batches.flat().find(event => event.eventType === 'REPLAY')!;
  const replayData = replay.data as { events?: Array<{ timestamp?: number }> };
  expect(replayData.events?.length).toBeGreaterThan(0);
  expect(Math.min(...replayData.events!.map(event => event.timestamp ?? errorTime)))
    .toBeLessThan(errorTime - 1000);
});

test('unsampled session without error retention does not upload Replay', async ({ page }) => {
  const batches: Array<Array<Record<string, unknown>>> = [];
  page.on('request', request => {
    if (request.url() !== batchUrl || request.method() !== 'POST') return;
    try {
      const body = request.postDataJSON() as { events?: Array<Record<string, unknown>> };
      if (body.events) batches.push(body.events);
    } catch {
      // Ignore requests without a JSON batch body.
    }
  });

  await page.goto('/?replaySampleRate=0');
  await page.waitForTimeout(1000);
  await page.getByRole('button', { name: 'JS Error' }).click();
  await expect.poll(() => batches.flat().some(event => event.eventType === 'ERROR'), {
    timeout: 10_000,
    intervals: [500, 1000, 2000]
  }).toBe(true);
  await page.waitForTimeout(4000);
  expect(batches.flat().some(event => event.eventType === 'REPLAY')).toBe(false);
});
