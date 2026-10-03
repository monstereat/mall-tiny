import { expect, test } from '@playwright/test';

const batchUrl = 'http://localhost:8080/api/v1/envelope/batch';

test('Replay masks configured text and input selectors and blocks marked regions', async ({ page }) => {
  const replayBatches: Array<Array<Record<string, unknown>>> = [];
  page.on('request', request => {
    if (request.url() !== batchUrl || request.method() !== 'POST') return;
    try {
      const body = request.postDataJSON() as { events?: Array<Record<string, unknown>> };
      const replay = body.events?.filter(event => event.eventType === 'REPLAY') ?? [];
      if (replay.length > 0) replayBatches.push(replay);
    } catch {
      // Ignore malformed or non-JSON requests from the demo page.
    }
  });
  await page.route(batchUrl, route => route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: '{"code":0}'
  }));

  await page.goto('/?replayMaskSelector=%5Bdata-e2e-mask%5D&replayBlockSelector=%5Bdata-e2e-block%5D');
  await page.evaluate(() => {
    const fixture = document.createElement('section');
    fixture.innerHTML = [
      '<p data-e2e-mask>masked-private-text-e2e</p>',
      '<p data-e2e-block>blocked-private-text-e2e</p>',
      '<input value="private-input-e2e">',
      '<p>visible-replay-context-e2e</p>'
    ].join('');
    document.body.append(fixture);
  });

  await expect.poll(() => {
    const replayEvents = replayBatches.flat();
    return replayEvents.some(event => {
      const data = event.data as { events?: Array<unknown> } | undefined;
      const payload = JSON.stringify(data?.events ?? []);
      return payload.includes('visible-replay-context-e2e');
    });
  }, { timeout: 20_000, intervals: [500, 1000, 2000] }).toBe(true);

  const payload = JSON.stringify(replayBatches.flat().map(event => event.data));
  expect(payload).not.toContain('masked-private-text-e2e');
  expect(payload).not.toContain('blocked-private-text-e2e');
  expect(payload).not.toContain('private-input-e2e');
  expect(payload).toContain('visible-replay-context-e2e');
});
