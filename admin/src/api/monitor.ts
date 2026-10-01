import { request } from './http';

export interface MonitorProject {
  id: number;
  name: string;
  projectKey: string;
  platform: string;
  status: number;
}

export interface DashboardData {
  errorCount: number;
  affectedUsers: number;
  apiEvents: number;
  unresolvedIssues: number;
  errorTrend: Array<{ bucket: string; count: number }>;
  webVitals: Array<{ metric: string; value: number }>;
}

export interface MonitorIssue {
  id: number;
  projectId: number;
  fingerprint: string;
  title: string;
  status: string;
  eventCount: number;
  affectedUsers: number;
  firstSeen: string;
  lastSeen: string;
  latestRelease?: string;
}

export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages?: number;
}

export interface IssueEvent {
  event_id: string;
  event_time: string;
  session_id?: string;
  user_id?: string;
  release?: string;
  page_url?: string;
  payload: string;
}

export interface IssueDetail {
  issue: MonitorIssue | null;
  events: IssueEvent[];
}

export interface MonitorRelease {
  id: number;
  projectId: number;
  version: string;
  environment: string;
  gitCommit?: string;
  branchName?: string;
  sourceMapStatus: string;
  buildTime?: string;
  deployTime?: string;
}

export interface MonitorReplay {
  id: number;
  projectId: number;
  eventId: string;
  sessionId: string;
  releaseVersion?: string;
  objectKey: string;
  eventCount: number;
  startTime?: string;
  endTime?: string;
}

export interface AlertRule {
  id?: number;
  projectId?: number;
  name: string;
  metric: string;
  operator: string;
  thresholdValue: number;
  windowSeconds: number;
  durationSeconds: number;
  cooldownSeconds: number;
  level: string;
  webhookUrl?: string;
  enabled: number;
}

export interface AlertRecord {
  id: number;
  ruleId: number;
  metric: string;
  metricValue: number;
  thresholdValue: number;
  level: string;
  status: string;
  fingerprint?: string;
  message: string;
  triggeredAt: string;
}

export interface SourcePosition {
  source: string;
  line: number;
  column: number;
  name?: string;
  sourceContent?: string;
}

function projectUrl(projectKey: string, suffix: string) {
  return `/monitor/admin/${encodeURIComponent(projectKey)}${suffix}`;
}

export const monitorApi = {
  projects: () => request<MonitorProject[]>('/monitor/admin/projects'),
  dashboard: (projectKey: string, hours = 24) =>
    request<DashboardData>(projectUrl(projectKey, `/dashboard?hours=${hours}`)),
  issues: (projectKey: string, pageNum = 1, pageSize = 20, status = '') =>
    request<PageResult<MonitorIssue>>(projectUrl(projectKey,
      `/issues?pageNum=${pageNum}&pageSize=${pageSize}&status=${encodeURIComponent(status)}`)),
  issue: (projectKey: string, id: string | number) =>
    request<IssueDetail>(projectUrl(projectKey, `/issues/${id}`)),
  releases: (projectKey: string) =>
    request<MonitorRelease[]>(projectUrl(projectKey, '/releases')),
  replays: (projectKey: string, sessionId = '') =>
    request<MonitorReplay[]>(projectUrl(projectKey, `/replays?sessionId=${encodeURIComponent(sessionId)}`)),
  replay: (projectKey: string, replayId: number) =>
    request<unknown[]>(projectUrl(projectKey, `/replays/${replayId}`)),
  alertRules: (projectKey: string) =>
    request<AlertRule[]>(projectUrl(projectKey, '/alerts/rules')),
  alertRecords: (projectKey: string) =>
    request<AlertRecord[]>(projectUrl(projectKey, '/alerts/records')),
  createAlertRule: (projectKey: string, rule: AlertRule) =>
    request<AlertRule>(projectUrl(projectKey, '/alerts/rules'), {
      method: 'POST',
      body: JSON.stringify(rule)
    }),
  updateAlertRule: (projectKey: string, id: number, rule: AlertRule) =>
    request<AlertRule>(projectUrl(projectKey, `/alerts/rules/${id}`), {
      method: 'PUT',
      body: JSON.stringify(rule)
    }),
  resolveSourceMap: (
    projectKey: string,
    params: { version: string; environment?: string; bundleFile: string; line: number; column: number }
  ) => {
    const query = new URLSearchParams({
      version: params.version,
      environment: params.environment || 'production',
      bundleFile: params.bundleFile,
      line: String(params.line),
      column: String(params.column)
    });
    return request<SourcePosition | null>(projectUrl(projectKey, `/sourcemap/resolve?${query}`));
  }
};
