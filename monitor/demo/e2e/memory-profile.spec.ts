import { chromium, expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { existsSync } from 'node:fs';

const chromeExecutable = process.env.CHROME_EXECUTABLE_PATH
  ?? (existsSync('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')
    ? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
    : undefined);

test('Chrome SDK memory profile envelope is captured in an isolated context', async () => {
  test.skip(!chromeExecutable, 'Set CHROME_EXECUTABLE_PATH to a Chrome build with measureUserAgentSpecificMemory support');
  test.setTimeout(40_000);

  const browser = await chromium.launch({ executablePath: chromeExecutable, headless: true });
  try {
    const page = await browser.newPage();
    const runId = randomUUID();
    const testUserId = `memory-profile-e2e-${runId}`;
    const sendToBackend = process.env.MONITOR_MEMORY_PROFILE_LIVE_INGEST === '1';
    const batches: Array<Array<Record<string, unknown>>> = [];
    let profileEventId: string | undefined;
    let ingestStatus: number | undefined;
    await page.addInitScript(() => {
      const supported = typeof performance.measureUserAgentSpecificMemory === 'function';
      Object.defineProperty(window, '__nativeMemoryMeasurementAvailable', { value: supported });
      // The native measurement can take seconds or minutes; the standalone Chrome probe covers it.
      Object.defineProperty(performance, 'measureUserAgentSpecificMemory', {
        configurable: true,
        value: async () => ({ bytes: 42_000_000 })
      });
    });

    await page.route('http://localhost:5174/**', async route => {
      if (route.request().resourceType() !== 'document') {
        await route.continue();
        return;
      }
      const response = await route.fetch();
      await route.fulfill({
        response,
        headers: {
          ...response.headers(),
          'cross-origin-opener-policy': 'same-origin',
          'cross-origin-embedder-policy': 'require-corp'
        }
      });
    });

    await page.route('**/api/v1/envelope/batch', async route => {
      const body = route.request().postDataJSON() as { events?: Array<Record<string, unknown>> };
      if (body.events) {
        batches.push(body.events);
        const profiles = body.events.filter(event => {
          const data = event.data as { name?: string } | undefined;
          return event.eventType === 'PROFILE'
            && data?.name === 'JavaScript Memory'
            && event.userId === testUserId;
        });
        if (profiles.length > 0) {
          profileEventId = String(profiles[0].eventId);
          if (sendToBackend) {
            const response = await route.fetch({ postData: JSON.stringify({ events: profiles }) });
            ingestStatus = response.status();
            await route.fulfill({ response });
            return;
          }
        }
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: '{"code":0}' });
    });

    await page.goto(`http://localhost:5174/?memoryProfile=${runId}`, { waitUntil: 'domcontentloaded' });
    const capabilities = await page.evaluate(() => ({
      secure: isSecureContext,
      isolated: crossOriginIsolated,
      nativeApiAvailable: (window as Window & { __nativeMemoryMeasurementAvailable?: boolean })
        .__nativeMemoryMeasurementAvailable
    }));
    expect(capabilities).toEqual({ secure: true, isolated: true, nativeApiAvailable: true });

    await expect.poll(() => batches.flat().some(event => {
      const data = event.data as { name?: string; unit?: string; samples?: Array<{ value?: number }> } | undefined;
      return event.eventType === 'PROFILE'
        && data?.name === 'JavaScript Memory'
        && data.unit === 'bytes'
        && event.userId === testUserId
        && Number.isFinite(data.samples?.[0]?.value)
        && (data.samples?.[0]?.value ?? 0) > 0;
    }), { timeout: 25_000, intervals: [1000, 2000] }).toBe(true);
    if (sendToBackend) {
      expect(profileEventId).toBeTruthy();
      expect(ingestStatus).toBe(200);
      console.log(`memory-profile-live-ingest ${JSON.stringify({ eventId: profileEventId, userId: testUserId })}`);
    }
  } finally {
    await browser.close();
  }
});
