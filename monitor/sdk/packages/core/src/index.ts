export type MonitorEventType = 'ERROR' | 'PERFORMANCE' | 'BEHAVIOR' | 'REPLAY' | 'METRIC' | 'PROFILE' | 'SPAN';

export interface MonitorClientOptions {
  endpoint: string;
  projectId: string;
  ingestKey: string;
  release?: string;
  environment?: string;
  sampleRate?: number;
  /** Send session lifecycle markers for Release Health. Defaults to true. */
  releaseHealth?: boolean;
  /** Opt-in probability for browser JavaScript CPU profiles. Defaults to 0. */
  profileSampleRate?: number;
  /** Duration of each CPU profile window in milliseconds. Defaults to 60000. */
  profileIntervalMs?: number;
  /** Requested JS profiler sample interval in milliseconds. Defaults to 10. */
  profileSampleIntervalMs?: number;
  /** Opt-in probability for browser JavaScript memory samples. Defaults to 0. */
  profileMemorySampleRate?: number;
  /** Time between JavaScript memory samples in milliseconds. Defaults to 60000. */
  profileMemoryIntervalMs?: number;
  batchSize?: number;
  flushInterval?: number;
  sessionId?: string;
  userId?: string;
  maxQueueSize?: number;
  persistQueue?: boolean;
  retryBaseDelay?: number;
  /** Optional filtering/rewriting before an event enters the persistent queue. Return null to drop it. */
  beforeSend?: (event: MonitorEventEnvelope) => MonitorEventEnvelope | null;
  /** Independent business/page sampling. Defaults to 1 for exact analytics. */
  analyticsSampleRate?: number;
}

export interface MonitorTrackOptions {
  pageUrl?: string;
  sampleRate?: number;
}

export interface MonitorPageOptions {
  url?: string;
  name?: string;
  referrer?: string;
  properties?: Record<string, unknown>;
}

export interface MonitorEventInput {
  eventType: MonitorEventType;
  data: Record<string, unknown>;
  timestamp?: number;
  pageUrl?: string;
  traceId?: string;
}

export interface MonitorEventEnvelope {
  eventId: string;
  projectId: string;
  eventType: MonitorEventType;
  timestamp: number;
  sessionId: string;
  userId?: string;
  release?: string;
  environment: string;
  pageUrl: string;
  sdkVersion: string;
  traceId?: string;
  device: Record<string, unknown>;
  data: Record<string, unknown>;
}

interface QueuedMonitorEvent extends MonitorEventEnvelope {
  traceparent?: string;
}

interface SelfProfilerTrace {
  resources: string[];
  frames: Array<{ name: string; resourceId?: number; line?: number; column?: number }>;
  stacks: Array<{ frameId: number; parentId?: number }>;
  samples: Array<{ stackId?: number }>;
}

interface SelfProfiler {
  readonly sampleInterval: number;
  stop(): Promise<SelfProfilerTrace>;
}

type SelfProfilerConstructor = new (options: { sampleInterval: number; maxBufferSize: number }) => SelfProfiler;

interface MemoryMeasurement {
  bytes: number;
}

interface MemoryPerformance extends Performance {
  measureUserAgentSpecificMemory?: () => Promise<MemoryMeasurement>;
}

const SDK_VERSION = '0.2.0';

function randomId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function clampRate(value: number | undefined): number {
  if (value == null) return 1;
  if (!Number.isFinite(value)) return 0;
  return Math.max(0, Math.min(1, value));
}

function matchingTraceparent(value: string | undefined, traceId: string | undefined): string | undefined {
  const match = value?.trim().match(/^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/i);
  if (!match || !traceId
      || match[1].toLowerCase() !== traceId.toLowerCase()
      || /^0+$/.test(match[1])
      || /^0+$/.test(match[2])) {
    return undefined;
  }
  return value?.trim().toLowerCase();
}

function analyticsUrl(value: string): string {
  const url = new URL(value, typeof location === 'undefined' ? 'https://localhost/' : location.href);
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('Unsupported analytics URL');
  const route = /^#!?\//.test(url.hash) ? url.hash.split('?')[0] : '';
  return url.origin + url.pathname + route;
}

function businessProperties(properties: Record<string, unknown>): Record<string, unknown> {
  const sensitive = new Set(['password', 'passwd', 'pwd', 'token', 'accesstoken', 'refreshtoken',
    'authorization', 'cookie', 'setcookie', 'secret', 'apikey', 'ingestkey', 'releasekey']);
  const visit = (value: unknown, depth: number): unknown => {
    if (depth > 3) throw new Error('Business properties are too deep');
    if (value === null || typeof value === 'boolean') return value;
    if (typeof value === 'string' && value.length <= 1024) return value;
    if (typeof value === 'number' && Number.isFinite(value)) return value;
    if (Array.isArray(value) && value.length <= 32) return value.map(item => visit(item, depth + 1));
    if (value && typeof value === 'object'
        && [Object.prototype, null].includes(Object.getPrototypeOf(value))) {
      const entries = Object.entries(value);
      if (entries.length > 32) throw new Error('Too many business properties');
      return Object.fromEntries(entries.map(([key, item]) => {
        if (!/^[A-Za-z0-9_.-]{1,64}$/.test(key)) throw new Error('Invalid business property');
        const normalized = key.toLowerCase().replace(/[^a-z0-9]/g, '');
        return [key, sensitive.has(normalized) ? '[Filtered]' : visit(item, depth + 1)];
      }));
    }
    throw new Error('Invalid business property value');
  };
  const result = visit(properties, 0) as Record<string, unknown>;
  if (!result || Array.isArray(result) || typeof result !== 'object') throw new Error('Properties must be an object');
  if (new TextEncoder().encode(JSON.stringify(result)).byteLength > 16 * 1024) {
    throw new Error('Business properties are too large');
  }
  return result;
}

export class MonitorClient {
  private readonly queue: QueuedMonitorEvent[] = [];
  private readonly errorListeners = new Set<(input: MonitorEventInput) => void>();
  private sessionId: string;
  private userId?: string;
  private timer?: ReturnType<typeof setInterval>;
  private retryTimer?: ReturnType<typeof setTimeout>;
  private retryAttempt = 0;
  private flushing = false;
  private profileTimer?: ReturnType<typeof setTimeout>;
  private profiler?: SelfProfiler;
  private profileStartedAt = 0;
  private memoryProfileTimer?: ReturnType<typeof setTimeout>;
  private memoryProfileEnabled = false;
  private closed = false;
  private businessPage?: { id: string; url: string; sampleRate: number; sessionId: string; userId?: string };

  constructor(private readonly options: MonitorClientOptions) {
    this.sessionId = options.sessionId ?? randomId();
    this.userId = options.userId;
    this.restoreQueue();
    this.startReleaseHealthSession();
    this.startProfiler();
    this.startMemoryProfiler();

    const interval = options.flushInterval ?? 5000;
    if (interval > 0) {
      this.timer = setInterval(() => {
        void this.flush().catch(() => undefined);
      }, interval);
    }
  }

  setUser(userId?: string): void {
    if (this.closed || userId === this.userId) return;
    if (this.options.releaseHealth === false) {
      this.userId = userId;
      return;
    }
    this.captureSessionLifecycle('end');
    this.userId = userId;
    this.sessionId = randomId();
    this.startReleaseHealthSession();
  }

  identify(userId?: string): void {
    if (userId !== undefined && (typeof userId !== 'string' || userId.length > 128)) return;
    this.setUser(userId || undefined);
  }

  onError(listener: (input: MonitorEventInput) => void): () => void {
    this.errorListeners.add(listener);
    return () => this.errorListeners.delete(listener);
  }

  capture(input: MonitorEventInput, traceparent?: string, bypassSampling = false): string | null {
    const sampleRate = clampRate(this.options.sampleRate);
    if (!bypassSampling && input.eventType !== 'ERROR' && (sampleRate <= 0 || Math.random() >= sampleRate)) {
      return null;
    }

    const eventId = randomId();
    const pageOwner = input.eventType === 'BEHAVIOR' && input.data.category === 'page'
      && input.data.event === 'page_dwell' && input.data.pageViewId === this.businessPage?.id
      ? this.businessPage : undefined;
    let event: QueuedMonitorEvent = {
      eventId,
      projectId: this.options.projectId,
      eventType: input.eventType,
      timestamp: input.timestamp ?? Date.now(),
      sessionId: pageOwner?.sessionId ?? this.sessionId,
      userId: pageOwner ? pageOwner.userId : this.userId,
      release: this.options.release,
      environment: this.options.environment ?? 'production',
      pageUrl: input.pageUrl ?? (typeof location === 'undefined' ? '' : location.href),
      sdkVersion: SDK_VERSION,
      traceId: input.traceId,
      device: this.device(),
      data: input.data,
      traceparent: matchingTraceparent(traceparent, input.traceId)
    };

    const prepared = this.prepareForQueue(event);
    if (!prepared) return null;
    event = prepared;

    this.enqueue(event);
    if (input.eventType === 'ERROR') {
      for (const listener of this.errorListeners) {
        try {
          listener({ ...input, data: event.data, pageUrl: event.pageUrl });
        } catch {
          // Replay integrations must never break application error reporting.
        }
      }
    }
    if (this.queue.length >= (this.options.batchSize ?? 20)) {
      void this.flush().catch(() => undefined);
    }
    return eventId;
  }

  captureException(error: Error, extra: Record<string, unknown> = {}): string | null {
    return this.capture({
      eventType: 'ERROR',
      data: {
        name: error.name,
        message: error.message,
        stack: error.stack,
        ...extra,
        mechanism: 'manual',
        unhandled: false
      }
    });
  }

  capturePerformance(metric: string, value: number, extra: Record<string, unknown> = {}): string | null {
    return this.capture({
      eventType: 'PERFORMANCE',
      data: { metric, value, ...extra }
    });
  }

  captureBehavior(
    category: string,
    data: Record<string, unknown> = {},
    traceId?: string,
    traceparent?: string
  ): string | null {
    return this.capture({
      eventType: 'BEHAVIOR',
      data: { category, ...data },
      traceId
    }, traceparent);
  }

  track(event: string, properties: Record<string, unknown> = {}, options: MonitorTrackOptions = {}): string | null {
    if (this.closed) return null;
    try {
      if (event.length > 64 || !/^[a-z][a-z0-9]*(?:_[a-z0-9]+)*$/.test(event)) return null;
      const rate = clampRate(options.sampleRate ?? this.options.analyticsSampleRate);
      if (!Number.isFinite(rate) || rate <= 0 || Math.random() >= rate) return null;
      return this.capture({
        eventType: 'BEHAVIOR',
        pageUrl: analyticsUrl(options.pageUrl ?? (typeof location === 'undefined' ? '/' : location.href)),
        data: { category: 'business', event, properties: businessProperties(properties), analyticsSampleRate: rate }
      }, undefined, true);
    } catch {
      return null;
    }
  }

  page(options: MonitorPageOptions = {}): string | null {
    if (this.closed) return null;
    try {
      const rate = clampRate(this.options.analyticsSampleRate);
      this.businessPage = undefined;
      if (!Number.isFinite(rate) || rate <= 0 || Math.random() >= rate) return null;
      if (options.name !== undefined && (typeof options.name !== 'string' || options.name.length > 64)) return null;
      const url = analyticsUrl(options.url ?? (typeof location === 'undefined' ? '/' : location.href));
      const id = randomId();
      const result = this.capture({
        eventType: 'BEHAVIOR', pageUrl: url,
        data: { category: 'page', event: 'page_view', pageViewId: id, name: options.name,
          referrer: options.referrer ? analyticsUrl(options.referrer) : undefined,
          properties: businessProperties(options.properties ?? {}), analyticsSampleRate: rate }
      }, undefined, true);
      if (!result) return null;
      this.businessPage = { id, url, sampleRate: rate, sessionId: this.sessionId, userId: this.userId };
      return id;
    } catch {
      this.businessPage = undefined;
      return null;
    }
  }

  capturePageDwell(pageViewId: string, pageUrl: string, durationMs: number): string | null {
    if (this.closed) return null;
    const page = this.businessPage;
    if (!page || page.id !== pageViewId || !Number.isFinite(durationMs) || durationMs < 0 || durationMs > 86_400_000) {
      return null;
    }
    try {
      if (analyticsUrl(pageUrl) !== page.url) return null;
      return this.capture({
        eventType: 'BEHAVIOR', pageUrl: page.url,
        data: { category: 'page', event: 'page_dwell', pageViewId, durationMs, analyticsSampleRate: page.sampleRate }
      }, undefined, true);
    } catch {
      return null;
    }
  }

  recordMetric(
    name: string,
    value: number,
    options: {
      metricType?: 'counter' | 'gauge' | 'distribution';
      unit?: string;
      tags?: Record<string, string | number | boolean>;
      timestamp?: number;
      traceId?: string;
      traceparent?: string;
    } = {}
  ): string | null {
    return this.capture({
      eventType: 'METRIC',
      timestamp: options.timestamp,
      traceId: options.traceId,
      data: {
        name,
        metricType: options.metricType ?? 'gauge',
        value,
        unit: options.unit,
        tags: options.tags,
        exemplar: options.traceId ? { traceId: options.traceId } : undefined
      }
    }, options.traceparent);
  }

  recordProfile(
    samples: Array<{ stack: string[]; value: number }>,
    options: { name?: string; unit?: string; timestamp?: number } = {}
  ): string | null {
    return this.capture({
      eventType: 'PROFILE',
      timestamp: options.timestamp,
      data: {
        format: 'collapsed',
        name: options.name ?? 'CPU',
        unit: options.unit ?? 'samples',
        samples
      }
    });
  }

  async flush(keepalive = false): Promise<void> {
    if (this.flushing || this.queue.length === 0) return;

    if (typeof navigator !== 'undefined' && navigator.onLine === false) {
      this.persistQueue();
      this.scheduleRetry();
      return;
    }

    this.flushing = true;
    try {
      const batchSize = Math.max(1, Math.min(100, this.options.batchSize ?? 20));
      const endpoint = this.options.endpoint.replace(/\/$/, '') + '/batch';
      while (this.queue.length > 0) {
        const events = this.takeBatch(batchSize);
        this.persistQueue();
        const traceparent = events[0].traceparent;
        const requestEvents = events.map(({ traceparent: _traceparent, ...event }) => event);
        const headers: Record<string, string> = {
          'Content-Type': 'application/json',
          'X-Monitor-Key': this.options.ingestKey
        };
        if (traceparent) headers.traceparent = traceparent;

        try {
          const response = await fetch(endpoint, {
            method: 'POST',
            headers,
            body: JSON.stringify({ events: requestEvents }),
            keepalive
          });
          if (!response.ok) {
            throw new Error(`monitor ingest failed: ${response.status}`);
          }
        } catch (error) {
          this.queue.unshift(...events);
          this.trimQueue();
          this.persistQueue();
          throw error;
        }

        this.retryAttempt = 0;
        this.clearRetry();
        this.persistQueue();
      }
    } catch (error) {
      this.scheduleRetry();
      throw error;
    } finally {
      this.flushing = false;
    }
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.captureSessionLifecycle('end');
    this.closed = true;
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
    this.clearRetry();
    if (this.memoryProfileTimer) {
      clearTimeout(this.memoryProfileTimer);
      this.memoryProfileTimer = undefined;
    }
    try {
      await this.stopProfiler();
      await this.flush(true);
    } finally {
      this.persistQueue();
    }
  }

  getEndpoint(): string {
    return this.options.endpoint;
  }

  getSessionId(): string {
    return this.sessionId;
  }

  private enqueue(event: MonitorEventEnvelope): void {
    this.queue.push(event);
    this.trimQueue();
    this.persistQueue();
  }

  private startReleaseHealthSession(): void {
    if (this.options.releaseHealth === false || this.closed) return;
    this.captureSessionLifecycle('start');
    void this.flush().catch(() => undefined);
  }

  private captureSessionLifecycle(action: 'start' | 'end'): void {
    if (this.options.releaseHealth === false) return;
    this.capture({
      eventType: 'BEHAVIOR',
      data: { category: 'session', action }
    }, undefined, true);
  }

  private startProfiler(): void {
    if (this.closed) return;
    if (Math.random() >= clampRate(this.options.profileSampleRate ?? 0)) return;
    const Profiler = (globalThis as typeof globalThis & { Profiler?: SelfProfilerConstructor }).Profiler;
    if (!Profiler) return;

    try {
      this.profileStartedAt = Date.now();
      const requestedInterval = Math.max(1, Math.min(100, this.options.profileSampleIntervalMs ?? 10));
      this.profiler = new Profiler({ sampleInterval: requestedInterval, maxBufferSize: 6000 });
      const actualInterval = Number.isFinite(this.profiler.sampleInterval) && this.profiler.sampleInterval > 0
        ? this.profiler.sampleInterval
        : requestedInterval;
      const safeDuration = Math.max(1000, actualInterval * 6000);
      const duration = Math.max(1000, Math.min(60_000, this.options.profileIntervalMs ?? 60_000, safeDuration));
      this.profileTimer = setTimeout(() => {
        void this.stopProfiler().then(() => this.startProfiler()).catch(() => undefined);
      }, duration);
    } catch {
      this.profiler = undefined;
    }
  }

  private startMemoryProfiler(): void {
    if (this.closed || Math.random() >= clampRate(this.options.profileMemorySampleRate ?? 0)) return;
    const performanceApi = globalThis.performance as MemoryPerformance | undefined;
    if (!globalThis.isSecureContext || globalThis.crossOriginIsolated !== true
        || typeof performanceApi?.measureUserAgentSpecificMemory !== 'function') return;

    this.memoryProfileEnabled = true;
    this.scheduleMemoryProfile();
  }

  private scheduleMemoryProfile(): void {
    if (!this.memoryProfileEnabled || this.closed) return;
    const interval = Math.max(10_000, Math.min(600_000, this.options.profileMemoryIntervalMs ?? 60_000));
    const delay = Math.round(interval * (0.9 + Math.random() * 0.2));
    this.memoryProfileTimer = setTimeout(() => {
      this.memoryProfileTimer = undefined;
      void this.collectMemoryProfile().finally(() => this.scheduleMemoryProfile());
    }, delay);
  }

  private async collectMemoryProfile(): Promise<void> {
    const performanceApi = globalThis.performance as MemoryPerformance | undefined;
    const measure = performanceApi?.measureUserAgentSpecificMemory;
    if (!this.memoryProfileEnabled || this.closed || typeof measure !== 'function') return;
    try {
      const result = await measure.call(performanceApi);
      if (!Number.isFinite(result.bytes) || result.bytes <= 0) return;
      this.recordProfile([{ stack: ['JavaScript memory (estimated)'], value: result.bytes }], {
        name: 'JavaScript Memory',
        unit: 'bytes'
      });
    } catch {
      // Memory measurement is optional and must not interrupt application telemetry.
    }
  }

  private async stopProfiler(): Promise<void> {
    if (this.profileTimer) {
      clearTimeout(this.profileTimer);
      this.profileTimer = undefined;
    }
    const profiler = this.profiler;
    this.profiler = undefined;
    if (!profiler) return;

    try {
      const trace = await profiler.stop();
      const counts = new Map<string, number>();
      for (const sample of trace.samples) {
        if (!Number.isInteger(sample.stackId)) continue;
        const frames: string[] = [];
        let stack: SelfProfilerTrace['stacks'][number] | undefined = trace.stacks[sample.stackId as number];
        let remaining = trace.stacks.length;
        while (stack && remaining-- > 0) {
          const frame = trace.frames[stack.frameId];
          if (frame) {
            const resource = Number.isInteger(frame.resourceId)
              ? trace.resources[frame.resourceId as number]
              : undefined;
            const path = resource ? this.profileResourcePath(resource) : '';
            const location = `${path}${Number.isInteger(frame.line) ? `:${frame.line}` : ''}${Number.isInteger(frame.column) ? `:${frame.column}` : ''}`;
            const name = String(frame.name || '(anonymous)');
            frames.push((location ? `${name}@${location}` : name).slice(0, 96));
          }
          stack = Number.isInteger(stack.parentId) ? trace.stacks[stack.parentId as number] : undefined;
        }
        if (frames.length === 0) continue;
        const collapsedStack = frames.reverse().slice(-64).join(';');
        if (counts.has(collapsedStack) || counts.size < 200) {
          counts.set(collapsedStack, (counts.get(collapsedStack) ?? 0) + 1);
        }
      }

      if (counts.size > 0) {
        this.recordProfile([...counts].map(([stack, value]) => ({ stack: stack.split(';'), value })), {
          name: 'JavaScript CPU',
          unit: 'samples',
          timestamp: this.profileStartedAt
        });
      }
    } catch {
      // Profiling is optional and must not interrupt application telemetry.
    }
  }

  private profileResourcePath(resource: string): string {
    try {
      const url = new URL(resource, typeof location === 'undefined' ? undefined : location.href);
      return url.pathname.slice(0, 300);
    } catch {
      return resource.split(/[?#]/, 1)[0].slice(0, 300);
    }
  }

  private takeBatch(batchSize: number): QueuedMonitorEvent[] {
    const traceparent = this.queue[0].traceparent;
    const batch: QueuedMonitorEvent[] = [];
    const remaining: QueuedMonitorEvent[] = [];

    for (const event of this.queue) {
      if (event.traceparent === traceparent && batch.length < batchSize) {
        batch.push(event);
      } else {
        remaining.push(event);
      }
    }

    this.queue.splice(0, this.queue.length, ...remaining);
    return batch;
  }

  private trimQueue(): void {
    const maxQueueSize = Math.max(100, this.options.maxQueueSize ?? 1000);
    if (this.queue.length > maxQueueSize) {
      this.queue.splice(0, this.queue.length - maxQueueSize);
    }
  }

  private scheduleRetry(): void {
    if (this.retryTimer) return;
    const base = Math.max(500, this.options.retryBaseDelay ?? 1000);
    const delay = Math.min(30000, base * 2 ** Math.min(this.retryAttempt, 5));
    this.retryAttempt += 1;
    this.retryTimer = setTimeout(() => {
      this.retryTimer = undefined;
      void this.flush().catch(() => undefined);
    }, delay);
  }

  private clearRetry(): void {
    if (this.retryTimer) {
      clearTimeout(this.retryTimer);
      this.retryTimer = undefined;
    }
  }

  private storageKey(): string {
    return `__observe_queue__:${this.options.projectId}`;
  }

  private prepareForQueue(event: QueuedMonitorEvent): QueuedMonitorEvent | null {
    if (!event || event.projectId !== this.options.projectId || typeof event.eventId !== 'string'
        || typeof event.sessionId !== 'string' || !Number.isFinite(event.timestamp)
        || !['ERROR', 'PERFORMANCE', 'BEHAVIOR', 'REPLAY', 'METRIC', 'PROFILE', 'SPAN'].includes(event.eventType)) {
      return null;
    }
    const eventId = event.eventId;
    const eventType = event.eventType;
    const traceparent = event.traceparent;
    try {
      const transformed = this.options.beforeSend ? this.options.beforeSend(event) : event;
      if (!transformed || transformed.eventId !== eventId || transformed.projectId !== this.options.projectId
          || transformed.eventType !== eventType || !transformed.data || typeof transformed.data !== 'object'
          || Array.isArray(transformed.data) || typeof transformed.sessionId !== 'string'
          || !Number.isFinite(transformed.timestamp)) return null;
      return { ...transformed, traceparent: matchingTraceparent(traceparent, transformed.traceId) };
    } catch {
      return null;
    }
  }

  private restoreQueue(): void {
    if (this.options.persistQueue === false || typeof localStorage === 'undefined') return;
    try {
      const raw = localStorage.getItem(this.storageKey());
      if (!raw) return;
      const events = JSON.parse(raw) as QueuedMonitorEvent[];
      if (!Array.isArray(events)) return;
      this.queue.push(...events
        .map(event => this.prepareForQueue(event))
        .filter((event): event is QueuedMonitorEvent => event !== null));
      this.trimQueue();
      this.persistQueue();
    } catch {
      // Ignore malformed or inaccessible storage. Monitoring must never break business code.
    }
  }

  private persistQueue(): void {
    if (this.options.persistQueue === false || typeof localStorage === 'undefined') return;
    try {
      if (this.queue.length === 0) {
        localStorage.removeItem(this.storageKey());
      } else {
        localStorage.setItem(this.storageKey(), JSON.stringify(this.queue));
      }
    } catch {
      // Storage quota/privacy mode failures are intentionally ignored.
    }
  }

  private device(): Record<string, unknown> {
    if (typeof navigator === 'undefined') return {};
    return {
      userAgent: navigator.userAgent,
      language: navigator.language,
      viewport: typeof window === 'undefined' ? undefined : {
        width: window.innerWidth,
        height: window.innerHeight
      }
    };
  }
}
