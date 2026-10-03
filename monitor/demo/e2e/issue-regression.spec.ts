import { expect, test, type Request } from '@playwright/test';
import { dirname } from 'node:path';
import { mkdir, writeFile } from 'node:fs/promises';

const serverUrl = 'http://localhost:8080';
const releaseHealthServerUrl = process.env.RELEASE_HEALTH_SERVER_URL ?? serverUrl;
const batchUrl = `${serverUrl}/api/v1/envelope/batch`;

test.describe.configure({ retries: 0 });

type MonitorIssue = {
  id: number;
  fingerprint: string;
  title: string;
  status: string;
  eventCount: number;
  resolvedAt: string | null;
  regressedAt: string | null;
};

type MonitorReleaseHealth = {
  release: string;
  environment: string;
  sessions: number;
  crashedSessions: number;
  crashFreeSessionsRate: number;
  users: number;
  crashedUsers: number;
  crashFreeUsersRate: number;
  unhandledErrors: number;
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
  const releaseHealthUrl = `${releaseHealthServerUrl}/monitor/admin/demo-web/release-health?hours=168`;
  const eventTimes: number[] = [];
  const observedErrors: Array<Record<string, unknown>> = [];
  let lastReleaseHealth: Record<string, unknown> | null = null;
  let lastDeletionStatus: Record<string, unknown> | null = null;
  let lastDeletionFailure: Record<string, unknown> | null = null;
  let cleanupAttempted = false;

  const observeError = (message = errorMessage) => page.waitForRequest(candidate => {
    if (candidate.url() !== batchUrl || candidate.method() !== 'POST') return false;
    try {
      const body = candidate.postDataJSON() as { events?: Array<Record<string, unknown>> };
      return body.events?.some(event => {
        const data = event.data as Record<string, unknown> | undefined;
        return event.eventType === 'ERROR' && data?.message === message;
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

  const fetchReleaseHealth = async (): Promise<MonitorReleaseHealth | null> => {
    const response = await request.get(releaseHealthUrl, { headers });
    if (!response.ok()) {
      lastReleaseHealth = { status: response.status() };
      return null;
    }
    const body = await response.json() as { data: MonitorReleaseHealth[] };
    const matchingRows = body.data.filter(row => row.release === release && row.environment === 'development');
    lastReleaseHealth = { status: response.status(), matchingRows };
    return matchingRows[0] ?? null;
  };

  const recordEventTime = async (browserRequest: Request, expectedUnhandled?: boolean) => {
    const body = browserRequest.postDataJSON() as {
      events: Array<{
        eventId: string;
        eventType: string;
        timestamp: number;
        sessionId: string;
        release?: string;
        environment: string;
        data?: { name?: string; message?: string; mechanism?: string; unhandled?: boolean };
      }>;
    };
    const errorEvent = body.events.find(event => event.eventType === 'ERROR' && event.data?.message === errorMessage);
    expect(errorEvent).toBeTruthy();
    if (expectedUnhandled !== undefined) {
      expect(errorEvent!.data?.unhandled).toBe(expectedUnhandled);
    }
    eventTimes.push(errorEvent!.timestamp);
    const response = await browserRequest.response();
    expect(response, 'telemetry batch receives an ingest response').not.toBeNull();
    observedErrors.push({
      event: {
        eventId: errorEvent!.eventId,
        timestamp: errorEvent!.timestamp,
        sessionId: errorEvent!.sessionId,
        release: errorEvent!.release,
        environment: errorEvent!.environment,
        data: {
          name: errorEvent!.data?.name,
          message: errorEvent!.data?.message,
          mechanism: errorEvent!.data?.mechanism,
          unhandled: errorEvent!.data?.unhandled
        }
      },
      batchSize: body.events.length,
      ingestStatus: response!.status()
    });
    expect(response!.ok(), 'telemetry batch is accepted by ingest').toBeTruthy();
  };

  const deleteRunData = async () => {
    cleanupAttempted = true;
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
    const previewCounts = JSON.parse(previewBody.data.previewCountsJson) as {
      error_event: number;
      behavior_event: number;
    };
    expect(previewCounts.error_event).toBeGreaterThanOrEqual(eventTimes.length);
    expect(previewCounts.behavior_event).toBeGreaterThan(0);
    const executeResponse = await request.post(
      `${serverUrl}/monitor/admin/demo-web/data-deletion/${previewBody.data.id}/execute`,
      { headers, data: { previewToken: previewBody.data.previewToken } }
    );
    if (!executeResponse.ok()) {
      lastDeletionFailure = { jobId: previewBody.data.id, executeStatus: executeResponse.status() };
    }
    expect(executeResponse.ok(), `data deletion execute returned HTTP ${executeResponse.status()}`).toBeTruthy();
    await expect.poll(async () => {
      const response = await request.get(`${serverUrl}/monitor/admin/demo-web/data-deletion/${previewBody.data.id}`, { headers });
      if (!response.ok()) return null;
      const body = await response.json() as { data: { status: string; stage?: string; leaseUntil?: string | null } };
      const leaseRemainingMs = body.data.leaseUntil ? Date.parse(body.data.leaseUntil) - Date.now() : null;
      lastDeletionStatus = {
        jobId: previewBody.data.id,
        status: body.data.status,
        stage: body.data.stage,
        leaseUntil: body.data.leaseUntil ?? null,
        leaseRemainingMs
      };
      if (body.data.stage === 'LOKI_DELETE_DISCOVER' && leaseRemainingMs !== null) {
        expect(leaseRemainingMs, 'E2E config applies the short Loki polling lease').toBeLessThanOrEqual(60_000);
      }
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
    await recordEventTime(await browserEvent);

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
    await recordEventTime(await browserEvent);
    await expect.poll(async () => {
      issue = await fetchIssue();
      return issue?.regressedAt ?? null;
    }, { timeout: 60_000, intervals: [500, 1000, 2000] }).not.toBeNull();
    expect(issue?.status).toBe('unresolved');
    expect(issue?.eventCount).toBeGreaterThanOrEqual(2);
    expect(issue?.regressedAt).toBeTruthy();

    for (let index = 0; index < 2; index += 1) {
      browserEvent = observeError();
      await page.evaluate(message => window.dispatchEvent(new ErrorEvent('error', {
        error: new Error(message),
        message
      })), errorMessage);
      await recordEventTime(await browserEvent, true);
    }

    await expect.poll(async () => (await fetchReleaseHealth())?.unhandledErrors ?? 0,
      { timeout: 60_000, intervals: [500, 1000, 2000] }).toBeGreaterThanOrEqual(2);
    const health = await fetchReleaseHealth();
    expect(health?.sessions).toBe(1);
    expect(health?.crashedSessions).toBe(1);
    expect(health?.crashFreeSessionsRate).toBe(0);
    expect(health?.users).toBe(1);
    expect(health?.crashedUsers).toBe(1);
    expect(health?.crashFreeUsersRate).toBe(0);

    await deleteRunData();
    await expect.poll(async () => (await fetchIssue())?.id ?? null, { timeout: 30_000 }).toBeNull();
    await expect.poll(async () => (await fetchReleaseHealth())?.sessions ?? 0, { timeout: 30_000 }).toBe(0);
    cleanupComplete = true;
  } catch (error) {
    let issueEvents: Array<Record<string, unknown>> = [];
    if (issue) {
      try {
        const detailResponse = await request.get(`${serverUrl}/monitor/admin/demo-web/issues/${issue.id}?eventLimit=50`, { headers });
        const detailBody = detailResponse.ok()
          ? await detailResponse.json() as { data?: { events?: Array<Record<string, unknown>> } }
          : null;
        issueEvents = (detailBody?.data?.events ?? []).map(event => {
          let payload: Record<string, unknown> = {};
          try {
            const raw = event.payload;
            payload = typeof raw === 'string' ? JSON.parse(raw) as Record<string, unknown> : raw as Record<string, unknown>;
          } catch {
            // Preserve a compact record if a stored payload cannot be decoded.
          }
          const data = payload.data as Record<string, unknown> | undefined;
          return {
            eventId: event.event_id,
            eventTime: event.event_time,
            release: event.release,
            environment: event.environment,
            message: data?.message,
            mechanism: data?.mechanism,
            unhandled: data?.unhandled
          };
        });
      } catch (diagnosticError) {
        issueEvents = [{ diagnosticError: diagnosticError instanceof Error ? diagnosticError.message : String(diagnosticError) }];
      }
    }
    const diagnostics = {
      runId,
      release,
      userId,
      issue,
      observedErrors,
      issueEvents,
      lastReleaseHealth,
      lastDeletionStatus,
      lastDeletionFailure,
      failure: error instanceof Error ? error.message : String(error)
    };
    const diagnosticBody = JSON.stringify(diagnostics, null, 2);
    await test.info().attach('release-health-e2e-diagnostics.json', {
      body: diagnosticBody,
      contentType: 'application/json'
    });
    const outputPath = test.info().outputPath('release-health-e2e-diagnostics.json');
    await mkdir(dirname(outputPath), { recursive: true });
    await writeFile(outputPath, diagnosticBody, 'utf8');
    throw error;
  } finally {
    if (!cleanupComplete && !cleanupAttempted) {
      try {
        await deleteRunData();
      } catch (cleanupError) {
        console.error(`Best-effort data deletion cleanup failed: ${cleanupError instanceof Error ? cleanupError.message : String(cleanupError)}`);
      }
    }
  }
});
