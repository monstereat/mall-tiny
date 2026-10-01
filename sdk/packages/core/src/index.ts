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

const SDK_VERSION = '0.1.0';

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

export class MonitorClient {
  private readonly queue: MonitorEventEnvelope[] = [];
  private readonly sessionId: string;
  private userId?: string;
  private timer?: ReturnType<typeof setInterval>;
  private flushing = false;

  constructor(private readonly options: MonitorClientOptions) {
    this.sessionId = options.sessionId ?? randomId();
    this.userId = options.userId;

    const interval = options.flushInterval ?? 5000;
    if (interval > 0) {
      this.timer = setInterval(() => {
        void this.flush();
      }, interval);
    }
  }

  setUser(userId?: string): void {
    this.userId = userId;
  }

  capture(input: MonitorEventInput): string | null {
    const sampleRate = clampRate(this.options.sampleRate);
    if (input.eventType !== 'ERROR' && Math.random() > sampleRate) {
      return null;
    }

    const eventId = randomId();
    const event: MonitorEventEnvelope = {
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
      data: input.data
    };

    this.queue.push(event);
    if (this.queue.length >= (this.options.batchSize ?? 20)) {
      void this.flush();
    }
    return eventId;
  }

  async flush(keepalive = false): Promise<void> {
    if (this.flushing || this.queue.length === 0) return;

    this.flushing = true;
    const batchSize = Math.max(1, Math.min(100, this.options.batchSize ?? 20));
    const events = this.queue.splice(0, batchSize);

    try {
      const endpoint = this.options.endpoint.replace(/\/$/, '') + '/batch';
      const response = await fetch(endpoint, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Monitor-Key': this.options.ingestKey
        },
        body: JSON.stringify({ events }),
        keepalive
      });
      if (!response.ok) {
        throw new Error(`monitor ingest failed: ${response.status}`);
      }
    } catch (error) {
      this.queue.unshift(...events);
      throw error;
    } finally {
      this.flushing = false;
    }

    if (this.queue.length >= batchSize) {
      queueMicrotask(() => {
        void this.flush();
      });
    }
  }

  async close(): Promise<void> {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
    await this.flush(true);
  }

  getEndpoint(): string {
    return this.options.endpoint;
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
