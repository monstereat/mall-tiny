import { expect, test } from '@playwright/test';
import { randomBytes } from 'node:crypto';
import { gzipSync } from 'node:zlib';

const serverUrl = 'http://localhost:8080';

test('gzipped project OTLP server span appears under a browser span in the project trace waterfall', async ({ page, request }) => {
  await page.goto('/');
  const apiRequestPromise = page.waitForRequest(candidate => candidate.url() === `${serverUrl}/admin/info`);
  const apiResponsePromise = page.waitForResponse(response => response.url() === `${serverUrl}/admin/info`);
  const browserSpanBatchPromise = page.waitForRequest(candidate => {
    if (candidate.url() !== `${serverUrl}/api/v1/envelope/batch` || candidate.method() !== 'POST') return false;
    try {
      const body = candidate.postDataJSON() as { events?: Array<Record<string, unknown>> };
      return body.events?.some(event => {
        const data = event.data as Record<string, unknown> | undefined;
        return event.eventType === 'SPAN' && String(data?.description).includes('/admin/info');
      }) ?? false;
    } catch {
      return false;
    }
  });
  await page.getByRole('button', { name: /API Trace Probe/ }).click();
  const [apiRequest, apiResponse, browserSpanBatch] = await Promise.all([
    apiRequestPromise,
    apiResponsePromise,
    browserSpanBatchPromise
  ]);
  expect(apiResponse.ok()).toBeTruthy();

  const requestTraceparent = (await apiRequest.allHeaders()).traceparent;
  const traceparentMatch = requestTraceparent?.match(/^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/i);
  expect(traceparentMatch, `valid browser traceparent: ${requestTraceparent}`).not.toBeNull();
  const traceId = traceparentMatch![1].toLowerCase();
  const browserSpanId = traceparentMatch![2].toLowerCase();
  const browserBatch = browserSpanBatch.postDataJSON() as { events: Array<Record<string, unknown>> };
  const browserSpanEvent = browserBatch.events.find(event => {
    const data = event.data as Record<string, unknown> | undefined;
    return event.eventType === 'SPAN' && String(data?.description).includes('/admin/info');
  });
  expect(browserSpanEvent).toBeTruthy();
  const browserSpanData = browserSpanEvent!.data as Record<string, unknown>;
  expect(String(browserSpanEvent!.traceId).toLowerCase()).toBe(traceId);
  expect(String(browserSpanData.spanId).toLowerCase()).toBe(browserSpanId);

  const spanId = randomBytes(8).toString('hex');
  const parentSpanId = browserSpanId;
  const startedAt = Date.now();
  const startTimeUnixNano = BigInt(startedAt) * 1_000_000n;
  const otlpPayload = {
    resourceSpans: [{
      resource: {
        attributes: [
          { key: 'service.name', value: { stringValue: 'otlp-e2e-service' } },
          { key: 'service.version', value: { stringValue: `e2e-${startedAt}` } },
          { key: 'deployment.environment.name', value: { stringValue: 'e2e' } }
        ]
      },
      scopeSpans: [{
        spans: [{
          traceId,
          spanId,
          parentSpanId,
          name: 'checkout.confirm',
          kind: 2,
          startTimeUnixNano: startTimeUnixNano.toString(),
          endTimeUnixNano: (startTimeUnixNano + 25_000_000n).toString(),
          attributes: [
            { key: 'http.response.status_code', value: { intValue: 201 } },
            { key: 'http.request.header.authorization', value: { stringValue: 'must-not-be-stored' } }
          ],
          status: { code: 1 }
        }]
      }]
    }]
  };

  const exportResponse = await request.post(`${serverUrl}/api/v1/otlp/demo-web/v1/traces`, {
    headers: {
      'X-Monitor-Key': 'dev-monitor-key',
      'Content-Type': 'application/json',
      'Content-Encoding': 'gzip'
    },
    data: gzipSync(Buffer.from(JSON.stringify(otlpPayload)))
  });
  expect(exportResponse.ok(), await exportResponse.text()).toBeTruthy();

  const loginResponse = await request.post(`${serverUrl}/admin/login`, {
    data: { username: 'admin', password: 'macro123' }
  });
  expect(loginResponse.ok()).toBeTruthy();
  const loginBody = await loginResponse.json() as { data: { token: string } };
  const headers = { Authorization: `Bearer ${loginBody.data.token}` };
  let storedSpan: Record<string, unknown> | undefined;

  await expect.poll(async () => {
    const response = await request.get(`${serverUrl}/monitor/admin/demo-web/traces/${traceId}/spans`, { headers });
    if (!response.ok()) return false;
    const body = await response.json() as { data: Array<Record<string, unknown>> };
    storedSpan = body.data.find(span => String(span.spanId).toLowerCase() === spanId);
    return Boolean(storedSpan);
  }, { timeout: 60_000, intervals: [500, 1000, 2000] }).toBe(true);

  expect(storedSpan).toMatchObject({
    traceId,
    spanId,
    parentSpanId,
    source: 'server',
    serviceName: 'otlp-e2e-service',
    kind: 'server',
    description: 'checkout.confirm',
    status: 'ok',
    statusCode: 201
  });
  let storedBrowserSpan: Record<string, unknown> | undefined;
  await expect.poll(async () => {
    const response = await request.get(`${serverUrl}/monitor/admin/demo-web/traces/${traceId}/spans`, { headers });
    if (!response.ok()) return false;
    const body = await response.json() as { data: Array<Record<string, unknown>> };
    storedBrowserSpan = body.data.find(span => String(span.spanId).toLowerCase() === browserSpanId);
    return Boolean(storedBrowserSpan);
  }, { timeout: 60_000, intervals: [500, 1000, 2000] }).toBe(true);
  expect(storedBrowserSpan?.source).toBe('browser');
  expect(String(storedSpan!.parentSpanId).toLowerCase()).toBe(String(storedBrowserSpan!.spanId).toLowerCase());
  expect(JSON.stringify(storedSpan)).not.toContain('must-not-be-stored');
});
