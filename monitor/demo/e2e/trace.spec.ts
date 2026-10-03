import { expect, test } from '@playwright/test';
import { dirname } from 'node:path';
import { mkdir, writeFile } from 'node:fs/promises';

const apiUrl = 'http://localhost:8080/admin/info';
const batchUrl = 'http://localhost:8080/api/v1/envelope/batch';
const jaegerUrl = 'http://localhost:16686';
let browserTraceDiagnostics: Record<string, unknown> = {};

test.afterEach(async ({}, testInfo) => {
  if (testInfo.title.startsWith('browser API probe') && testInfo.status !== testInfo.expectedStatus) {
    const body = JSON.stringify(browserTraceDiagnostics, null, 2);
    console.error(`Browser trace E2E diagnostics:\n${body}`);
    const outputPath = testInfo.outputPath('browser-trace-e2e-diagnostics.json');
    await mkdir(dirname(outputPath), { recursive: true });
    await writeFile(outputPath, body, 'utf8');
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

test('browser fetch HTTP span reaches the trace span API with its parent context', async ({ page, request }) => {
  const runId = `http-span-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
  const userId = `issue-regression-e2e-${runId}`;
  const traceId = runId.replace(/[^0-9a-f]/gi, '').padEnd(32, 'a').slice(0, 32).toLowerCase();
  const parentSpanId = Math.random().toString(16).slice(2).padEnd(16, 'b').slice(0, 16).toLowerCase();
  const release = `e2e-${runId}`;
  const authResponse = await request.post('http://localhost:8080/admin/login', {
    data: { username: 'admin', password: 'macro123' }
  });
  expect(authResponse.ok()).toBeTruthy();
  const authBody = await authResponse.json() as { data: { token: string } };
  const headers = { Authorization: `Bearer ${authBody.data.token}` };
  const spansUrl = `http://localhost:8080/monitor/admin/demo-web/traces/${traceId}/spans`;
  let batchAccepted = false;
  let spanStartTime = Date.now();
  let spanEventTime = spanStartTime;
  let spanId = '';
  let cleanupDiagnostics: Record<string, unknown> = { phase: 'not-started' };

  const deleteRunData = async () => {
    if (!batchAccepted) return;
    cleanupDiagnostics = { phase: 'wait-for-span-persistence', runId, userId, traceId, spanId, spanEventTime };
    try {
      await expect.poll(() => Date.now(), { timeout: 20_000, intervals: [500, 1000, 2000] })
        .toBeGreaterThan(spanEventTime + 10_000);
      const rangeEnd = new Date(Date.now() - 2_000).toISOString();
      cleanupDiagnostics = { ...cleanupDiagnostics, phase: 'preview', rangeEnd };
      const previewResponse = await request.post('http://localhost:8080/monitor/admin/demo-web/data-deletion/preview', {
        headers,
        data: {
          from: new Date(spanEventTime - 60_000).toISOString(),
          to: rangeEnd,
          userId
        }
      });
      const previewText = await previewResponse.text();
      cleanupDiagnostics = { ...cleanupDiagnostics, previewStatus: previewResponse.status(), previewBody: previewText };
      expect(previewResponse.ok(), 'user-scoped telemetry cleanup preview is available').toBeTruthy();
      const previewBody = JSON.parse(previewText) as {
        data: { id: number; previewToken: string; previewCountsJson?: string };
      };
      cleanupDiagnostics = {
        ...cleanupDiagnostics,
        phase: 'execute',
        deletionJobId: previewBody.data.id,
        previewCountsJson: previewBody.data.previewCountsJson
      };
      const executeResponse = await request.post(
        `http://localhost:8080/monitor/admin/demo-web/data-deletion/${previewBody.data.id}/execute`,
        { headers, data: { previewToken: previewBody.data.previewToken } }
      );
      cleanupDiagnostics = { ...cleanupDiagnostics, executeStatus: executeResponse.status() };
      expect(executeResponse.ok(), 'user-scoped telemetry cleanup starts').toBeTruthy();
      await expect.poll(async () => {
        const [spansResponse, jobResponse] = await Promise.all([
          request.get(spansUrl, { headers }),
          request.get(`http://localhost:8080/monitor/admin/demo-web/data-deletion/${previewBody.data.id}`, { headers })
        ]);
        if (!spansResponse.ok() || !jobResponse.ok()) return false;
        const spansBody = await spansResponse.json() as { data: Array<Record<string, unknown>> };
        const jobBody = await jobResponse.json() as {
          data: { status: string; stage?: string; errorMessage?: string };
        };
        cleanupDiagnostics = {
          ...cleanupDiagnostics,
          phase: 'wait-for-clickhouse-cleanup',
          deletionStatus: jobBody.data.status,
          deletionStage: jobBody.data.stage,
          deletionError: jobBody.data.errorMessage
        };
        return jobBody.data.status !== 'FAILED'
          && !spansBody.data.some(span => String(span.spanId).toLowerCase() === spanId);
      }, { timeout: 90_000, intervals: [1000, 2000, 5000] }).toBe(true);
      cleanupDiagnostics = { ...cleanupDiagnostics, phase: 'complete' };
    } catch (error) {
      cleanupDiagnostics = { ...cleanupDiagnostics, failure: error instanceof Error ? error.message : String(error) };
      await test.info().attach('browser-http-span-cleanup-diagnostics.json', {
        body: JSON.stringify(cleanupDiagnostics, null, 2),
        contentType: 'application/json'
      });
      throw error;
    }
  };

  try {
    await page.goto(`/?issueRegression=${encodeURIComponent(runId)}&release=${encodeURIComponent(release)}`);
    const parentTraceparent = `00-${traceId}-${parentSpanId}-01`;
    const spanBatchRequest = page.waitForRequest(candidate => {
      if (candidate.url() !== batchUrl || candidate.method() !== 'POST') return false;
      try {
        const body = candidate.postDataJSON() as { events?: Array<Record<string, unknown>> };
        return body.events?.some(event => {
          const data = event.data as Record<string, unknown> | undefined;
          return event.eventType === 'SPAN'
            && String(event.traceId).toLowerCase() === traceId
            && String(data?.description).includes('/admin/info');
        }) ?? false;
      } catch {
        return false;
      }
    });
    const fetchResponse = await page.evaluate(async ({ url, traceparent }) => {
      const response = await fetch(url, { headers: { traceparent } });
      return { status: response.status };
    }, { url: apiUrl, traceparent: parentTraceparent });
    expect(fetchResponse.status).toBe(200);

    const batchRequest = await spanBatchRequest;
    const batchResponse = await batchRequest.response();
    expect(batchResponse, 'span batch receives an ingest response').not.toBeNull();
    batchAccepted = batchResponse!.ok();
    expect(batchAccepted, `backend accepts the Browser SPAN event (HTTP ${batchResponse!.status()})`).toBeTruthy();

    const batchBody = batchRequest.postDataJSON() as { events: Array<Record<string, unknown>> };
    const spanEvent = batchBody.events.find(event => {
      const data = event.data as Record<string, unknown> | undefined;
      return event.eventType === 'SPAN'
        && String(event.traceId).toLowerCase() === traceId
        && String(data?.description).includes('/admin/info');
    });
    expect(spanEvent, 'Browser SDK emits an HTTP SPAN into the ingest batch').toBeTruthy();
    const spanData = spanEvent!.data as Record<string, unknown>;
    spanStartTime = Number(spanData.startTime) || Date.now();
    spanEventTime = spanStartTime + (Number(spanData.durationMs) || 0);
    spanId = String(spanData.spanId ?? '').toLowerCase();
    expect(spanId).toMatch(/^[0-9a-f]{16}$/);
    expect(spanId).not.toBe(parentSpanId);
    expect(String(spanEvent!.traceId).toLowerCase()).toBe(traceId);
    expect(String(spanData.parentSpanId).toLowerCase()).toBe(parentSpanId);
    expect(String(spanData.op)).toMatch(/^http\./);

    let storedSpan: Record<string, unknown> | undefined;
    await expect.poll(async () => {
      const response = await request.get(spansUrl, { headers });
      if (!response.ok()) return false;
      const body = await response.json() as { data: Array<Record<string, unknown>> };
      storedSpan = body.data.find(span => String(span.spanId).toLowerCase() === spanId);
      return Boolean(storedSpan);
    }, { timeout: 60_000, intervals: [500, 1000, 2000] }).toBe(true);
    expect(String(storedSpan!.traceId).toLowerCase()).toBe(traceId);
    expect(String(storedSpan!.parentSpanId).toLowerCase()).toBe(parentSpanId);
  } finally {
    await deleteRunData();
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
