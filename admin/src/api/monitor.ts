import { request } from './http';

export interface MonitorProject {
  id: number;
  name: string;
  projectKey: string;
  platform: string;
  status: number;
}

export interface ProjectCredentials {
  project: MonitorProject;
  ingestKey: string;
  releaseKey: string;
}

export interface MonitorProjectMember {
  id: number;
  projectId: number;
  adminId: number;
  role: 'OWNER' | 'MEMBER' | 'VIEWER';
}

export interface PerformanceData {
  summary: Array<{ metric: string; avgValue: number; p75: number; p95: number; samples: number }>;
  trend: Array<{ bucket: string; metric: string; value: number }>;
  recent: Array<{ event_time: string; page_url: string; release: string; environment: string; metric: string; value: number }>;
}

export interface ApiData {
  summary: Array<{ url: string; requests: number; avgRt: number; p95: number; failures: number }>;
  trend: Array<{ bucket: string; requests: number; failures: number; avgRt: number }>;
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
  environment?: string;
  page_url?: string;
  trace_id?: string;
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
  recoveredAt?: string;
}

export interface AlertSilence {
  id: string;
  scope: 'project' | 'rule' | 'issue';
  ruleId?: number;
  fingerprint?: string;
  reason?: string;
  createdAt: number;
  expiresAt: number;
}

export interface AlertDelivery {
  id: string;
  ruleId: number;
  alertRecordId: number;
  alertStatus: string;
  status: string;
  attempts: number;
  nextAttemptAt: number;
  createdAt: number;
  updatedAt: number;
  lastError?: string;
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
  createProject: (payload: { name: string; projectKey: string; platform: string }) =>
    request<ProjectCredentials>('/monitor/admin/projects', {
      method: 'POST',
      body: JSON.stringify(payload)
    }),
  rotateProjectKeys: (projectKey: string) =>
    request<ProjectCredentials>(`/monitor/admin/projects/${encodeURIComponent(projectKey)}/rotate-keys`, {
      method: 'POST'
    }),
  projectMembers: (projectKey: string) =>
    request<MonitorProjectMember[]>(`/monitor/admin/projects/${encodeURIComponent(projectKey)}/members`),
  saveProjectMember: (projectKey: string, payload: { adminId: number; role: 'MEMBER' | 'VIEWER' }) =>
    request<MonitorProjectMember>(`/monitor/admin/projects/${encodeURIComponent(projectKey)}/members`, {
      method: 'PUT',
      body: JSON.stringify(payload)
    }),
  removeProjectMember: (projectKey: string, adminId: number) =>
    request<void>(`/monitor/admin/projects/${encodeURIComponent(projectKey)}/members/${adminId}`, {
      method: 'DELETE'
    }),
  dashboard: (projectKey: string, hours = 24, environment = '', release = '') => {
    const query = new URLSearchParams({ hours: String(hours) });
    if (environment) query.set('environment', environment);
    if (release) query.set('release', release);
    return request<DashboardData>(projectUrl(projectKey, `/dashboard?${query}`));
  },
  issues: (
    projectKey: string,
    pageNum = 1,
    pageSize = 20,
    status = '',
    hours = 720,
    release = ''
  ) => {
    const query = new URLSearchParams({
      pageNum: String(pageNum),
      pageSize: String(pageSize),
      status,
      hours: String(hours)
    });
    if (release) query.set('release', release);
    return request<PageResult<MonitorIssue>>(projectUrl(projectKey, `/issues?${query}`));
  },
  issue: (projectKey: string, id: string | number) =>
    request<IssueDetail>(projectUrl(projectKey, `/issues/${id}`)),
  updateIssueStatus: (projectKey: string, id: string | number, status: 'unresolved' | 'resolved' | 'ignored') =>
    request<MonitorIssue>(projectUrl(projectKey, `/issues/${id}/status?status=${status}`), {
      method: 'PATCH'
    }),
  performance: (projectKey: string, hours = 24, environment = '', release = '') => {
    const query = new URLSearchParams({ hours: String(hours) });
    if (environment) query.set('environment', environment);
    if (release) query.set('release', release);
    return request<PerformanceData>(projectUrl(projectKey, `/performance?${query}`));
  },
  apis: (projectKey: string, hours = 24, environment = '', release = '') => {
    const query = new URLSearchParams({ hours: String(hours) });
    if (environment) query.set('environment', environment);
    if (release) query.set('release', release);
    return request<ApiData>(projectUrl(projectKey, `/apis?${query}`));
  },
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
  alertDeliveries: (projectKey: string) =>
    request<AlertDelivery[]>(projectUrl(projectKey, '/alerts/deliveries')),
  alertSilences: (projectKey: string) =>
    request<AlertSilence[]>(projectUrl(projectKey, '/alerts/silences')),
  createAlertSilence: (projectKey: string, payload: {
    scope: AlertSilence['scope']; ruleId?: number; fingerprint?: string;
    reason?: string; durationSeconds: number;
  }) => request<AlertSilence>(projectUrl(projectKey, '/alerts/silences'), {
    method: 'POST',
    body: JSON.stringify(payload)
  }),
  deleteAlertSilence: (projectKey: string, silenceId: string) =>
    request<void>(projectUrl(projectKey, `/alerts/silences/${encodeURIComponent(silenceId)}`), {
      method: 'DELETE'
    }),
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
