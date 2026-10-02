export type MonitorEventType = 'ERROR' | 'PERFORMANCE' | 'BEHAVIOR' | 'REPLAY';

export interface MonitorClientOptions {
  endpoint: string;
  projectId: string;
  ingestKey: string;
  release?: string;
  environment?: string;
  sampleRate?: number;
  batchSize?: number;
  flushInterval?: number;
  sessionId?: string;
  userId?: string;
  maxQueueSize?: number;
  persistQueue?: boolean;
  retryBaseDelay?: number;
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

const SDK_VERSION = '0.2.0';

function randomId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function clampRate(value: number | undefined): number {
  if (value == null) return 1;
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

export class MonitorClient {
  private readonly queue: QueuedMonitorEvent[] = [];
  private readonly sessionId: string;
  private userId?: string;
  private timer?: ReturnType<typeof setInterval>;
  private retryTimer?: ReturnType<typeof setTimeout>;
  private retryAttempt = 0;
  private flushing = false;

  constructor(private readonly options: MonitorClientOptions) {
    this.sessionId = options.sessionId ?? randomId();
    this.userId = options.userId;
    this.restoreQueue();

    const interval = options.flushInterval ?? 5000;
    if (interval > 0) {
      this.timer = setInterval(() => {
        void this.flush().catch(() => undefined);
      }, interval);
    }
  }

  setUser(userId?: string): void {
    this.userId = userId;
  }

  capture(input: MonitorEventInput, traceparent?: string): string | null {
    const sampleRate = clampRate(this.options.sampleRate);
    if (input.eventType !== 'ERROR' && Math.random() > sampleRate) {
      return null;
    }

    const eventId = randomId();
    const event: QueuedMonitorEvent = {
      eventId,
      projectId: this.options.projectId,
      eventType: input.eventType,
      timestamp: input.timestamp ?? Date.now(),
      sessionId: this.sessionId,
      userId: this.userId,
      release: this.options.release,
      environment: this.options.environment ?? 'production',
      pageUrl: input.pageUrl ?? (typeof location === 'undefined' ? '' : location.href),
      sdkVersion: SDK_VERSION,
      traceId: input.traceId,
      device: this.device(),
      data: input.data,
      traceparent: matchingTraceparent(traceparent, input.traceId)
    };

    this.enqueue(event);
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
        ...extra
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
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
    this.clearRetry();
    try {
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

  private restoreQueue(): void {
    if (this.options.persistQueue === false || typeof localStorage === 'undefined') return;
    try {
      const raw = localStorage.getItem(this.storageKey());
      if (!raw) return;
      const events = JSON.parse(raw) as QueuedMonitorEvent[];
      if (!Array.isArray(events)) return;
      this.queue.push(...events
        .filter(event => event.projectId === this.options.projectId)
        .map(event => ({
          ...event,
          traceparent: matchingTraceparent(event.traceparent, event.traceId)
        })));
      this.trimQueue();
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
