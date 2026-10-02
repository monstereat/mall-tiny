import { expect, test } from '@playwright/test';

const apiUrl = 'http://localhost:8080/admin/info';
const batchUrl = 'http://localhost:8080/api/v1/envelope/batch';
const jaegerUrl = 'http://localhost:16686';

function traceId(traceparent: string): string {
  const match = traceparent.match(/^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/i);
  expect(match, `valid W3C traceparent: ${traceparent}`).not.toBeNull();
  return match![1].toLowerCase();
}

test('browser API probe, SDK batch, Kafka producer and consumer share one trace', async ({ page, request }) => {
  await expect.poll(async () => {
    const health = await request.get('http://localhost:8081/actuator/health');
    return health.ok();
  }, { timeout: 120_000, intervals: [1000, 2000, 5000] }).toBe(true);

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
  await page.getByRole('button', { name: /API Trace Probe/ }).click();
  const apiResponse = await apiResponsePromise;
  expect(apiResponse.ok()).toBeTruthy();

  const apiRequestTraceparent = (await apiResponse.request().allHeaders()).traceparent;
  const responseTraceparent = (await apiResponse.allHeaders()).traceparent;
  expect(apiRequestTraceparent).toBeTruthy();
  expect(responseTraceparent).toBeTruthy();
  expect(traceId(responseTraceparent!)).toBe(traceId(apiRequestTraceparent!));

  const batchRequest = await behaviorBatchRequest;
  const batchHeaders = await batchRequest.allHeaders();
  const batchTraceparent = batchHeaders.traceparent;
  expect(batchTraceparent).toBe(responseTraceparent);

  const batchBody = batchRequest.postDataJSON() as { events: Array<Record<string, unknown>> };
  const behaviorEvent = batchBody.events.find(event => {
    const data = event.data as Record<string, unknown> | undefined;
    return event.eventType === 'BEHAVIOR'
      && data?.category === 'api'
      && String(data.url).includes('/admin/info');
  });
  expect(behaviorEvent).toBeTruthy();
  expect(String(behaviorEvent!.traceId).toLowerCase()).toBe(traceId(apiRequestTraceparent!));
  expect(traceId(batchTraceparent!)).toBe(traceId(apiRequestTraceparent!));

  const expectedTraceId = traceId(apiRequestTraceparent!);
  const responseParentSpanId = responseTraceparent!.split('-')[2].toLowerCase();
  await expect.poll(async () => {
    const traceResponse = await request.get(`${jaegerUrl}/api/traces/${expectedTraceId}`);
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
    if (!trace) return false;
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
    if (!apiProbe || !batchIngest) return false;
    const producer = spans.find(span => span.operationName === 'monitor-behavior-v1 send'
      && hasAncestor(span, batchIngest.spanID));
    if (!producer) return false;
    return spans.some(span => span.operationName === 'monitor.kafka.consume'
      && span.references?.some(reference => reference.refType === 'CHILD_OF'
        && reference.spanID.toLowerCase() === producer.spanID.toLowerCase()));
  }, { timeout: 60_000, intervals: [500, 1000, 2000] }).toBe(true);
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
