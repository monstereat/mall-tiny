import { expect, test, type Request } from '@playwright/test';

const serverUrl = 'http://localhost:8080';
const batchUrl = `${serverUrl}/api/v1/envelope/batch`;

type MonitorIssue = {
  id: number;
  fingerprint: string;
  title: string;
  status: string;
  eventCount: number;
  resolvedAt: string | null;
  regressedAt: string | null;
};

test('a real browser error reopens a resolved Issue as a regression without assignment', async ({ page, request }) => {
  const runId = `issue-regression-${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
  const userId = `issue-regression-e2e-${runId}`;
  const release = `e2e-${runId}`;
  const errorMessage = `issue regression probe ${runId}`;
  const authResponse = await request.post(`${serverUrl}/admin/login`, {
    data: {
      username: 'admin',
      password: 'macro123'
    }
  });
  expect(authResponse.ok()).toBeTruthy();
  const authBody = await authResponse.json() as { data: { token: string } };
  const headers = { Authorization: `Bearer ${authBody.data.token}` };
  const issueListUrl = `${serverUrl}/monitor/admin/demo-web/issues?pageNum=1&pageSize=100&hours=720&release=${encodeURIComponent(release)}`;
  const eventTimes: number[] = [];

  const observeError = () => page.waitForRequest(candidate => {
    if (candidate.url() !== batchUrl || candidate.method() !== 'POST') return false;
    try {
      const body = candidate.postDataJSON() as { events?: Array<Record<string, unknown>> };
      return body.events?.some(event => {
        const data = event.data as Record<string, unknown> | undefined;
        return event.eventType === 'ERROR' && data?.message === errorMessage;
      }) ?? false;
    } catch {
      return false;
    }
  }, { timeout: 30_000 });

  const fetchIssue = async (): Promise<MonitorIssue | null> => {
    const response = await request.get(issueListUrl, { headers });
    if (!response.ok()) return null;
    const body = await response.json() as { data: { records: MonitorIssue[] } };
    return body.data.records.find(issue => issue.title.includes(errorMessage)) ?? null;
  };

  const recordEventTime = (browserRequest: Request) => {
    const body = browserRequest.postDataJSON() as {
      events: Array<{ eventType: string; timestamp: number; data?: { message?: string } }>;
    };
    const errorEvent = body.events.find(event => event.eventType === 'ERROR' && event.data?.message === errorMessage);
    expect(errorEvent).toBeTruthy();
    eventTimes.push(errorEvent!.timestamp);
  };

  const deleteRunData = async () => {
    if (eventTimes.length) {
      const safeDeletionTime = Math.max(...eventTimes) + 10_000;
      await expect.poll(() => Date.now(), { timeout: 20_000 }).toBeGreaterThan(safeDeletionTime);
    } else {
      await page.waitForTimeout(5_000);
    }
    const previewResponse = await request.post(`${serverUrl}/monitor/admin/demo-web/data-deletion/preview`, {
      headers,
      data: {
        from: new Date(Date.now() - 13 * 24 * 60 * 60_000).toISOString(),
        to: new Date(Date.now() - 2_000).toISOString(),
        userId
      }
    });
    expect(previewResponse.ok()).toBeTruthy();
    const previewBody = await previewResponse.json() as {
      data: { id: number; previewToken: string; previewCountsJson: string };
    };
    const previewCounts = JSON.parse(previewBody.data.previewCountsJson) as { error_event: number };
    expect(previewCounts.error_event).toBeGreaterThanOrEqual(eventTimes.length);
    const executeResponse = await request.post(
      `${serverUrl}/monitor/admin/demo-web/data-deletion/${previewBody.data.id}/execute`,
      { headers, data: { previewToken: previewBody.data.previewToken } }
    );
    expect(executeResponse.ok()).toBeTruthy();
    await expect.poll(async () => {
      const response = await request.get(`${serverUrl}/monitor/admin/demo-web/data-deletion/${previewBody.data.id}`, { headers });
      if (!response.ok()) return null;
      const body = await response.json() as { data: { status: string } };
      return body.data.status;
    }, { timeout: 90_000, intervals: [1000, 2000, 5000] }).toBe('COMPLETED');
  };

  let issue: MonitorIssue | null = null;
  let cleanupComplete = false;
  try {
    await page.goto(`/?issueRegression=${encodeURIComponent(runId)}&release=${encodeURIComponent(release)}`);
    await page.waitForTimeout(1000);
    let browserEvent = observeError();
    await page.getByRole('button', { name: 'JS Error' }).click();
    recordEventTime(await browserEvent);

    await expect.poll(async () => {
      issue = await fetchIssue();
      return issue?.status ?? null;
    }, { timeout: 60_000, intervals: [500, 1000, 2000] }).toBe('unresolved');
    expect(issue).not.toBeNull();

    const resolveResponse = await request.patch(
      `${serverUrl}/monitor/admin/demo-web/issues/${issue!.id}/status?status=resolved`,
      { headers }
    );
    expect(resolveResponse.ok()).toBeTruthy();
    const resolvedBody = await resolveResponse.json() as { data: MonitorIssue };
    expect(resolvedBody.data.status).toBe('resolved');
    expect(resolvedBody.data.resolvedAt).toBeTruthy();

    browserEvent = observeError();
    await page.getByRole('button', { name: 'JS Error' }).click();
    recordEventTime(await browserEvent);
    await expect.poll(async () => {
      issue = await fetchIssue();
      return issue?.regressedAt ?? null;
    }, { timeout: 60_000, intervals: [500, 1000, 2000] }).not.toBeNull();
    expect(issue?.status).toBe('unresolved');
    expect(issue?.eventCount).toBeGreaterThanOrEqual(2);
    expect(issue?.regressedAt).toBeTruthy();

    await deleteRunData();
    await expect.poll(async () => (await fetchIssue())?.id ?? null, { timeout: 30_000 }).toBeNull();
    cleanupComplete = true;
  } finally {
    if (!cleanupComplete) await deleteRunData();
  }
});
