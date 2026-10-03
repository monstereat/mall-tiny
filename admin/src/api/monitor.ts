import { request } from './http';

export interface MonitorProject {
  id: number;
  tenantId: number;
  name: string;
  projectKey: string;
  platform: string;
  status: number;
}

export interface ProfileSummary {
  eventId: string;
  eventTime: string;
  name: string;
  unit: string;
  release: string;
  environment: string;
  sampleCount: number;
  totalValue: number;
}

export interface ProfileFlameNode {
  name: string;
  value: number;
  children: ProfileFlameNode[];
}

export interface ProfileDetail {
  profile: ProfileSummary;
  flamegraph: ProfileFlameNode[];
}

export interface MonitorSavedExploreQuery {
  id: number;
  name: string;
  criteria: {
    name: string;
    hours: number;
    type?: string;
    environment?: string;
    release?: string;
    traceId?: string;
    query?: string;
    userId?: string;
    tagKey?: string;
    tagValue?: string;
    groupBy?: string;
    aggregation?: string;
    field?: string;
    formula?: string;
    formulaMetrics?: string[];
  };
  createdBy: number;
  canModify: boolean;
  createTime?: string;
  updateTime?: string;
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

export interface MonitorTenant {
  id: number;
  name: string;
  tenantKey: string;
  status: number;
}

export interface MonitorTenantMember {
  id: number;
  tenantId: number;
  adminId: number;
  role: 'OWNER' | 'MEMBER' | 'VIEWER';
  customRoleId?: number | null;
}

export type MonitorTenantPermission = 'TENANT_MEMBER_READ' | 'TENANT_MEMBER_MANAGE' | 'TEAM_MANAGE'
  | 'AUDIT_READ' | 'SCIM_MANAGE' | 'ALERT_ROUTE_READ' | 'ALERT_ROUTE_MANAGE';

export interface MonitorTenantRole {
  id: number;
  tenantId: number;
  roleKey: string;
  name: string;
  permissions: MonitorTenantPermission[];
  createTime?: string;
  updateTime?: string;
}

export interface MonitorTeam {
  id: number;
  tenantId: number;
  name: string;
  teamKey: string;
  isDefault: number;
}

export interface MonitorTeamMember {
  id: number;
  tenantId: number;
  teamId: number;
  adminId: number;
  role: 'MEMBER' | 'VIEWER';
}

export interface MonitorScimToken {
  id: number;
  tenantId: number;
  name: string;
  createTime: string;
  lastUsedAt?: string;
  revokedAt?: string;
}

export interface MonitorScimTokenCreated {
  token: MonitorScimToken;
  value: string;
  baseUrl: string;
}

export interface MonitorTenantSamlConfig {
  tenantId: number;
  tenantKey: string;
  enabled: boolean;
  metadataXml: string;
  emailAttribute: string;
  updateTime?: string;
  loginUrl: string;
  metadataUrl: string;
  logoutUrl: string;
}

export interface MonitorTenantAuditLog {
  id: number;
  tenantId: number;
  actorAdminId: number;
  action: string;
  resourceType: string;
  resourceId: string;
  detailJson: string;
  createTime: string;
}

export interface MonitorCron {
  id: number;
  projectId: number;
  slug: string;
  name: string;
  scheduleType: 'interval' | 'crontab';
  schedule: string;
  timezone: string;
  checkinMarginSeconds: number;
  maxRuntimeSeconds: number;
  failureThreshold: number;
  recoveryThreshold: number;
  status: 'active' | 'disabled';
  healthStatus: 'unknown' | 'in_progress' | 'warning' | 'ok' | 'error';
  consecutiveFailures: number;
  consecutiveSuccesses: number;
  lastCheckinAt?: string;
  lastCheckinStatus?: string;
  nextCheckinAt: string;
}

export interface MonitorCronCheckIn {
  id: number;
  cronId: number;
  checkinId: string;
  status: 'in_progress' | 'ok' | 'error' | 'missed' | 'timed_out';
  environment: string;
  startedAt: string;
  completedAt?: string;
  durationMs?: number;
  message?: string;
}

export interface MonitorUptime {
  id: number;
  projectId: number;
  slug: string;
  name: string;
  url: string;
  method: 'GET' | 'HEAD';
  intervalSeconds: number;
  timeoutMs: number;
  expectedStatusCode: number;
  failureThreshold: number;
  recoveryThreshold: number;
  status: 'active' | 'disabled';
  currentStatus: 'unknown' | 'warning' | 'up' | 'down';
  consecutiveFailures: number;
  consecutiveSuccesses: number;
  checkedAt?: string;
  lastStatusCode?: number;
  lastDurationMs?: number;
  lastError?: string;
  nextCheckAt: string;
}

export interface MonitorUptimeCheck {
  id: number;
  uptimeCheckId: number;
  status: 'up' | 'down';
  checkedAt: string;
  responseStatus?: number;
  durationMs: number;
  message?: string;
}

export interface MonitorDataDeletionJob {
  id: number;
  projectId: number;
  projectKey: string;
  userId?: string;
  rangeStart: string;
  rangeEnd: string;
  status: 'PREVIEW' | 'QUEUED' | 'RUNNING' | 'FAILED' | 'COMPLETED';
  stage: string;
  previewToken?: string;
  previewCountsJson: string;
  deletedCountsJson: string;
  errorMessage?: string;
  createTime: string;
  startedAt?: string;
  finishedAt?: string;
}

export interface PerformanceData {
  summary: Array<{ metric: string; avgValue: number; p75: number; p95: number; samples: number }>;
  trend: Array<{ bucket: string; metric: string; value: number }>;
  recent: Array<{ event_time: string; page_url: string; release: string; environment: string; metric: string; value: number }>;
}

export interface MetricData {
  summary: Array<{ name: string; metricType: string; unit: string; samples: number; sum: number; avg: number; p50: number; p95: number; min: number; max: number }>;
  trend: Array<{ bucket: string; name: string; metricType: string; value: number; samples: number }>;
  recent: Array<{ event_time: string; name: string; metricType: string; value: number; unit: string; tags: string; trace_id: string }>;
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
  regressedAt?: string | null;
  newIssue: boolean;
  eventsLast24h: number;
  eventsPrevious24h: number;
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

export interface IssueAiAnalysis {
  summary: string;
  severity: 'low' | 'medium' | 'high' | 'critical';
  confidence: number;
  possibleCauses: string[];
  recommendations: string[];
  evidence: string[];
}

export interface MonitorLogEntry {
  timestamp: string;
  line: string;
  labels: Record<string, string>;
  metadata: Record<string, string>;
}

export interface MonitorLogSearchResult {
  traceId: string | null;
  entries: MonitorLogEntry[];
}

export interface MonitorExploreEvent {
  signal_type: 'error' | 'performance' | 'behavior' | 'replay' | 'metric' | 'profile' | 'logs';
  event_id: string;
  event_time: string;
  title: string;
  release: string;
  environment: string;
  page_url: string;
  trace_id: string;
  fingerprint: string;
  session_id: string;
  payload: string;
}

export interface MonitorExploreResult {
  events: MonitorExploreEvent[];
  hasMore: boolean;
  limit: number;
}

export interface MonitorExploreAggregationResult {
  groupBy: string;
  aggregation: string;
  field: string;
  buckets: Array<{ value: string; count: number; aggregateValue?: number }>;
}

export interface MonitorMetricFormulaResult {
  formula: string;
  points: Array<{ bucket: string; value: number }>;
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
  notificationRouteId?: number;
  enabled: number;
}

export interface AlertNotificationRoute {
  id: number;
  name: string;
  webhookUrl?: string;
  createTime?: string;
  updateTime?: string;
}

export interface AlertNotificationRouteOption {
  id: number;
  name: string;
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

export interface SourceMapStackFrame {
  index: number;
  function?: string | null;
  raw: string;
  generatedFile: string;
  generatedLine: number;
  generatedColumn: number;
  mapped: boolean;
  source?: string | null;
  line?: number | null;
  column?: number | null;
  name?: string | null;
  sourceContent?: string | null;
}

function projectUrl(projectKey: string, suffix: string) {
  return `/monitor/admin/${encodeURIComponent(projectKey)}${suffix}`;
}

export const monitorApi = {
  tenantSamlConfig: (tenantId: number) => request<MonitorTenantSamlConfig>(`/monitor/admin/tenants/${tenantId}/saml`),
  saveTenantSamlConfig: (tenantId: number, payload: Pick<MonitorTenantSamlConfig, 'enabled' | 'metadataXml' | 'emailAttribute'>) =>
    request<MonitorTenantSamlConfig>(`/monitor/admin/tenants/${tenantId}/saml`, { method: 'PUT', body: JSON.stringify(payload) }),
  exchangeSamlLoginCode: (code: string) => request<{ token: string; tokenHead: string }>(
    '/monitor/sso/exchange', { method: 'POST', body: JSON.stringify({ code }) }, false
  ),
  dataDeletionJobs: (projectKey: string) => request<MonitorDataDeletionJob[]>(projectUrl(projectKey, '/data-deletion')),
  previewDataDeletion: (projectKey: string, payload: { from: string; to: string; userId?: string }) =>
    request<MonitorDataDeletionJob>(projectUrl(projectKey, '/data-deletion/preview'), { method: 'POST', body: JSON.stringify(payload) }),
  executeDataDeletion: (projectKey: string, jobId: number, previewToken: string) =>
    request<MonitorDataDeletionJob>(projectUrl(projectKey, `/data-deletion/${jobId}/execute`), { method: 'POST', body: JSON.stringify({ previewToken }) }),
  dataDeletionJob: (projectKey: string, jobId: number) =>
    request<MonitorDataDeletionJob>(projectUrl(projectKey, `/data-deletion/${jobId}`)),
  retryDataDeletion: (projectKey: string, jobId: number) =>
    request<MonitorDataDeletionJob>(projectUrl(projectKey, `/data-deletion/${jobId}/retry`), { method: 'POST' }),
  tenants: () => request<MonitorTenant[]>('/monitor/admin/tenants'),
  createTenant: (payload: { name: string; tenantKey: string }) =>
    request<MonitorTenant>('/monitor/admin/tenants', { method: 'POST', body: JSON.stringify(payload) }),
  tenantTeams: (tenantId: number) => request<MonitorTeam[]>(`/monitor/admin/tenants/${tenantId}/teams`),
  createTenantTeam: (tenantId: number, payload: { name: string; teamKey: string }) =>
    request<MonitorTeam>(`/monitor/admin/tenants/${tenantId}/teams`, { method: 'POST', body: JSON.stringify(payload) }),
  tenantTeamMembers: (tenantId: number, teamId: number) =>
    request<MonitorTeamMember[]>(`/monitor/admin/tenants/${tenantId}/teams/${teamId}/members`),
  saveTenantTeamMember: (tenantId: number, teamId: number, payload: { adminId: number; role: MonitorTeamMember['role'] }) =>
    request<MonitorTeamMember>(`/monitor/admin/tenants/${tenantId}/teams/${teamId}/members`, { method: 'PUT', body: JSON.stringify(payload) }),
  removeTenantTeamMember: (tenantId: number, teamId: number, adminId: number) =>
    request<void>(`/monitor/admin/tenants/${tenantId}/teams/${teamId}/members/${adminId}`, { method: 'DELETE' }),
  tenantScimTokens: (tenantId: number) =>
    request<MonitorScimToken[]>(`/monitor/admin/tenants/${tenantId}/scim-tokens`),
  createTenantScimToken: (tenantId: number, name: string) =>
    request<MonitorScimTokenCreated>(`/monitor/admin/tenants/${tenantId}/scim-tokens`, { method: 'POST', body: JSON.stringify({ name }) }),
  revokeTenantScimToken: (tenantId: number, tokenId: number) =>
    request<void>(`/monitor/admin/tenants/${tenantId}/scim-tokens/${tokenId}`, { method: 'DELETE' }),
  tenantMembers: (tenantId: number) => request<MonitorTenantMember[]>(`/monitor/admin/tenants/${tenantId}/members`),
  saveTenantMember: (tenantId: number, payload: { adminId: number; role: MonitorTenantMember['role']; customRoleId?: number }) =>
    request<MonitorTenantMember>(`/monitor/admin/tenants/${tenantId}/members`, { method: 'PUT', body: JSON.stringify(payload) }),
  removeTenantMember: (tenantId: number, adminId: number) =>
    request<void>(`/monitor/admin/tenants/${tenantId}/members/${adminId}`, { method: 'DELETE' }),
  tenantAuditLogs: (tenantId: number, limit = 100) =>
    request<MonitorTenantAuditLog[]>(`/monitor/admin/tenants/${tenantId}/audit-logs?limit=${limit}`),
  tenantRoles: (tenantId: number) => request<MonitorTenantRole[]>(`/monitor/admin/tenants/${tenantId}/roles`),
  createTenantRole: (tenantId: number, payload: Pick<MonitorTenantRole, 'roleKey' | 'name' | 'permissions'>) =>
    request<MonitorTenantRole>(`/monitor/admin/tenants/${tenantId}/roles`, { method: 'POST', body: JSON.stringify(payload) }),
  updateTenantRole: (tenantId: number, roleId: number, payload: Pick<MonitorTenantRole, 'roleKey' | 'name' | 'permissions'>) =>
    request<MonitorTenantRole>(`/monitor/admin/tenants/${tenantId}/roles/${roleId}`, { method: 'PUT', body: JSON.stringify(payload) }),
  deleteTenantRole: (tenantId: number, roleId: number) =>
    request<void>(`/monitor/admin/tenants/${tenantId}/roles/${roleId}`, { method: 'DELETE' }),
  crons: (projectKey: string) => request<MonitorCron[]>(projectUrl(projectKey, '/crons')),
  createCron: (projectKey: string, payload: Omit<MonitorCron, 'id' | 'projectId' | 'healthStatus' | 'consecutiveFailures' | 'consecutiveSuccesses' | 'lastCheckinAt' | 'lastCheckinStatus' | 'nextCheckinAt'>) =>
    request<MonitorCron>(projectUrl(projectKey, '/crons'), { method: 'POST', body: JSON.stringify(payload) }),
  updateCron: (projectKey: string, cronId: number, payload: Omit<MonitorCron, 'id' | 'projectId' | 'healthStatus' | 'consecutiveFailures' | 'consecutiveSuccesses' | 'lastCheckinAt' | 'lastCheckinStatus' | 'nextCheckinAt'>) =>
    request<MonitorCron>(projectUrl(projectKey, `/crons/${cronId}`), { method: 'PUT', body: JSON.stringify(payload) }),
  deleteCron: (projectKey: string, cronId: number) =>
    request<void>(projectUrl(projectKey, `/crons/${cronId}`), { method: 'DELETE' }),
  cronCheckIns: (projectKey: string, cronId: number, limit = 50) =>
    request<MonitorCronCheckIn[]>(projectUrl(projectKey, `/crons/${cronId}/check-ins?limit=${limit}`)),
  uptimes: (projectKey: string) => request<MonitorUptime[]>(projectUrl(projectKey, '/uptime')),
  createUptime: (projectKey: string, payload: Omit<MonitorUptime, 'id' | 'projectId' | 'currentStatus' | 'consecutiveFailures' | 'consecutiveSuccesses' | 'checkedAt' | 'lastStatusCode' | 'lastDurationMs' | 'lastError' | 'nextCheckAt'>) =>
    request<MonitorUptime>(projectUrl(projectKey, '/uptime'), { method: 'POST', body: JSON.stringify(payload) }),
  updateUptime: (projectKey: string, uptimeId: number, payload: Omit<MonitorUptime, 'id' | 'projectId' | 'currentStatus' | 'consecutiveFailures' | 'consecutiveSuccesses' | 'checkedAt' | 'lastStatusCode' | 'lastDurationMs' | 'lastError' | 'nextCheckAt'>) =>
    request<MonitorUptime>(projectUrl(projectKey, `/uptime/${uptimeId}`), { method: 'PUT', body: JSON.stringify(payload) }),
  deleteUptime: (projectKey: string, uptimeId: number) =>
    request<void>(projectUrl(projectKey, `/uptime/${uptimeId}`), { method: 'DELETE' }),
  uptimeChecks: (projectKey: string, uptimeId: number, limit = 50) =>
    request<MonitorUptimeCheck[]>(projectUrl(projectKey, `/uptime/${uptimeId}/checks?limit=${limit}`)),
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
  analyzeIssue: (projectKey: string, id: string | number) =>
    request<IssueAiAnalysis>(projectUrl(projectKey, `/issues/${id}/ai-analysis`), { method: 'POST' }),
  logs: (projectKey: string, traceId?: string, hours = 24, limit = 200, text?: string) => {
    const query = new URLSearchParams({ hours: String(hours), limit: String(limit) });
    if (traceId) query.set('traceId', traceId);
    if (text) query.set('query', text);
    return request<MonitorLogSearchResult>(projectUrl(projectKey, `/logs?${query}`));
  },
  explore: (projectKey: string, params: {
    hours: number; type?: string; environment?: string; release?: string;
    traceId?: string; query?: string; userId?: string; tagKey?: string; tagValue?: string;
    limit?: number; offset?: number;
  }) => {
    const query = new URLSearchParams({ hours: String(params.hours), limit: String(params.limit || 100), offset: String(params.offset || 0) });
    if (params.type) query.set('type', params.type);
    if (params.environment) query.set('environment', params.environment);
    if (params.release) query.set('release', params.release);
    if (params.traceId) query.set('traceId', params.traceId);
    if (params.query) query.set('query', params.query);
    if (params.userId) query.set('userId', params.userId);
    if (params.tagKey) query.set('tagKey', params.tagKey);
    if (params.tagValue) query.set('tagValue', params.tagValue);
    return request<MonitorExploreResult>(projectUrl(projectKey, `/explore?${query}`));
  },
  exploreAggregation: (projectKey: string, params: {
    hours: number; type?: string; environment?: string; release?: string;
    traceId?: string; query?: string; userId?: string; tagKey?: string; tagValue?: string;
    groupBy?: string; aggregation?: string; field?: string;
  }) => {
    const query = new URLSearchParams({ hours: String(params.hours), groupBy: params.groupBy || 'signal',
      aggregation: params.aggregation || 'count', field: params.field || 'value' });
    if (params.type) query.set('type', params.type);
    if (params.environment) query.set('environment', params.environment);
    if (params.release) query.set('release', params.release);
    if (params.traceId) query.set('traceId', params.traceId);
    if (params.query) query.set('query', params.query);
    if (params.userId) query.set('userId', params.userId);
    if (params.tagKey) query.set('tagKey', params.tagKey);
    if (params.tagValue) query.set('tagValue', params.tagValue);
    return request<MonitorExploreAggregationResult>(projectUrl(projectKey, `/explore/aggregate?${query}`));
  },
  exploreMetricFormula: (projectKey: string, payload: {
    hours: number; environment?: string; release?: string; traceId?: string; query?: string;
    userId?: string; tagKey?: string; tagValue?: string; metricNames: string[]; formula: string;
  }) => request<MonitorMetricFormulaResult>(projectUrl(projectKey, '/explore/formula'), {
    method: 'POST', body: JSON.stringify(payload)
  }),
  savedExploreQueries: (projectKey: string) =>
    request<MonitorSavedExploreQuery[]>(projectUrl(projectKey, '/explore/saved-queries')),
  createSavedExploreQuery: (projectKey: string, payload: MonitorSavedExploreQuery['criteria']) =>
    request<MonitorSavedExploreQuery>(projectUrl(projectKey, '/explore/saved-queries'), {
      method: 'POST', body: JSON.stringify(payload)
    }),
  updateSavedExploreQuery: (projectKey: string, id: number, payload: MonitorSavedExploreQuery['criteria']) =>
    request<MonitorSavedExploreQuery>(projectUrl(projectKey, `/explore/saved-queries/${id}`), {
      method: 'PUT', body: JSON.stringify(payload)
    }),
  deleteSavedExploreQuery: (projectKey: string, id: number) =>
    request<void>(projectUrl(projectKey, `/explore/saved-queries/${id}`), { method: 'DELETE' }),
  issueByFingerprint: (projectKey: string, fingerprint: string) =>
    request<MonitorIssue | null>(projectUrl(projectKey, `/issues/by-fingerprint?fingerprint=${encodeURIComponent(fingerprint)}`)),
  updateIssueStatus: (projectKey: string, id: string | number, status: 'unresolved' | 'resolved' | 'ignored') =>
    request<MonitorIssue>(projectUrl(projectKey, `/issues/${id}/status?status=${status}`), {
      method: 'PATCH'
    }),
  updateIssuesStatus: (projectKey: string, issueIds: number[], status: 'unresolved' | 'resolved' | 'ignored') =>
    request<number>(projectUrl(projectKey, '/issues/status'), {
      method: 'PATCH',
      body: JSON.stringify({ issueIds, status })
    }),
  performance: (projectKey: string, hours = 24, environment = '', release = '') => {
    const query = new URLSearchParams({ hours: String(hours) });
    if (environment) query.set('environment', environment);
    if (release) query.set('release', release);
    return request<PerformanceData>(projectUrl(projectKey, `/performance?${query}`));
  },
  metrics: (projectKey: string, hours = 24, environment = '', release = '', name = '') => {
    const query = new URLSearchParams({ hours: String(hours) });
    if (environment) query.set('environment', environment);
    if (release) query.set('release', release);
    if (name) query.set('name', name);
    return request<MetricData>(projectUrl(projectKey, `/metrics?${query}`));
  },
  profiles: (projectKey: string, hours = 24, environment = '', release = '') => {
    const query = new URLSearchParams({ hours: String(hours) });
    if (environment) query.set('environment', environment);
    if (release) query.set('release', release);
    return request<ProfileSummary[]>(projectUrl(projectKey, `/profiles?${query}`));
  },
  profile: (projectKey: string, eventId: string) =>
    request<ProfileDetail>(projectUrl(projectKey, `/profiles/${encodeURIComponent(eventId)}`)),
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
  alertNotificationRoutes: (projectKey: string) =>
    request<AlertNotificationRouteOption[]>(projectUrl(projectKey, '/alerts/routes')),
  tenantAlertNotificationRoutes: (tenantId: number) =>
    request<AlertNotificationRoute[]>(`/monitor/admin/tenants/${tenantId}/alert-routes`),
  createTenantAlertNotificationRoute: (tenantId: number, payload: Pick<AlertNotificationRoute, 'name' | 'webhookUrl'>) =>
    request<AlertNotificationRoute>(`/monitor/admin/tenants/${tenantId}/alert-routes`, {
      method: 'POST', body: JSON.stringify(payload)
    }),
  updateTenantAlertNotificationRoute: (tenantId: number, id: number, payload: Pick<AlertNotificationRoute, 'name' | 'webhookUrl'>) =>
    request<AlertNotificationRoute>(`/monitor/admin/tenants/${tenantId}/alert-routes/${id}`, {
      method: 'PUT', body: JSON.stringify(payload)
    }),
  deleteTenantAlertNotificationRoute: (tenantId: number, id: number) =>
    request<void>(`/monitor/admin/tenants/${tenantId}/alert-routes/${id}`, { method: 'DELETE' }),
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
  },
  resolveSourceMapStack: (
    projectKey: string,
    params: {
      version: string;
      environment?: string;
      stack?: string;
      file?: string;
      line?: number;
      column?: number;
    }
  ) => request<SourceMapStackFrame[]>(projectUrl(projectKey, '/sourcemap/resolve-stack'), {
    method: 'POST',
    body: JSON.stringify(params)
  })
};
