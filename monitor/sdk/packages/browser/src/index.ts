import {
  MonitorClient,
  type MonitorClientOptions,
  type MonitorEventEnvelope
} from '@observe/core';

export interface BrowserMonitorOptions extends MonitorClientOptions {
  captureErrors?: boolean;
  capturePerformance?: boolean;
  captureFetch?: boolean;
  captureXhr?: boolean;
  captureClicks?: boolean;
  captureWhiteScreen?: boolean;
  captureResourceTiming?: boolean;
  captureNavigation?: boolean;
  whiteScreenDelay?: number;
  maxBreadcrumbs?: number;
}

export interface Breadcrumb {
  type: 'click' | 'route' | 'fetch' | 'xhr';
  timestamp: number;
  data: Record<string, unknown>;
}

export interface BrowserMonitor {
  client: MonitorClient;
  breadcrumbs: Breadcrumb[];
  destroy(): Promise<void>;
}

const SENSITIVE_QUERY_KEYS = new Set([
  'token', 'access_token', 'authorization', 'password', 'passwd',
  'session', 'sessionid', 'code', 'secret'
]);

function text(value: unknown, max = 200): string {
  const source = value == null ? '' : String(value);
  return source.length <= max ? source : source.slice(0, max);
}

function sanitizeUrl(raw: string): string {
  try {
    const url = new URL(raw, location.href);
    for (const key of [...url.searchParams.keys()]) {
      if (SENSITIVE_QUERY_KEYS.has(key.toLowerCase())) {
        url.searchParams.set(key, '[redacted]');
      }
    }
    return url.toString();
  } catch {
    return raw.split('#')[0];
  }
}

function requestUrl(input: RequestInfo | URL): string {
  if (typeof input === 'string') return sanitizeUrl(input);
  if (input instanceof URL) return sanitizeUrl(input.toString());
  return sanitizeUrl(input.url);
}

function isIngestUrl(url: string, endpoint: string): boolean {
  return url.startsWith(endpoint.replace(/\/$/, ''));
}

export function init(options: BrowserMonitorOptions): BrowserMonitor {
  const client = new MonitorClient(options);
  const breadcrumbs: Breadcrumb[] = [];
  const maxBreadcrumbs = Math.max(1, options.maxBreadcrumbs ?? 30);
  const cleanup: Array<() => void> = [];
  const observers: PerformanceObserver[] = [];
  const ingestPrefix = options.endpoint.replace(/\/$/, '');

  const addBreadcrumb = (breadcrumb: Omit<Breadcrumb, 'timestamp'>): void => {
    breadcrumbs.push({ ...breadcrumb, timestamp: Date.now() });
    while (breadcrumbs.length > maxBreadcrumbs) breadcrumbs.shift();
  };

  const breadcrumbSnapshot = (): Breadcrumb[] => breadcrumbs.map(item => ({
    ...item,
    data: { ...item.data }
  }));

  if (options.captureErrors !== false) {
    const errorHandler = (event: ErrorEvent | Event): void => {
      if (event instanceof ErrorEvent && event.error) {
        client.capture({
          eventType: 'ERROR',
          data: {
            name: event.error.name ?? 'Error',
            message: event.message,
            stack: event.error.stack,
            file: event.filename,
            line: event.lineno,
            column: event.colno,
            breadcrumbs: breadcrumbSnapshot()
          }
        });
        return;
      }

      const target = event.target;
      if (target instanceof HTMLElement) {
        const resource = target as HTMLElement & { src?: string; href?: string };
        client.capture({
          eventType: 'ERROR',
          data: {
            name: 'ResourceError',
            message: 'resource load failed',
            file: resource.src || resource.href || '',
            tagName: resource.tagName,
            breadcrumbs: breadcrumbSnapshot()
          }
        });
      }
    };

    const rejectionHandler = (event: PromiseRejectionEvent): void => {
      const reason = event.reason;
      client.capture({
        eventType: 'ERROR',
        data: {
          name: reason instanceof Error ? reason.name : 'UnhandledRejection',
          message: reason instanceof Error ? reason.message : text(reason),
          stack: reason instanceof Error ? reason.stack : undefined,
          breadcrumbs: breadcrumbSnapshot()
        }
      });
    };

    window.addEventListener('error', errorHandler, true);
    window.addEventListener('unhandledrejection', rejectionHandler);
    cleanup.push(() => window.removeEventListener('error', errorHandler, true));
    cleanup.push(() => window.removeEventListener('unhandledrejection', rejectionHandler));
  }

  if (options.captureClicks !== false) {
    const clickHandler = (event: MouseEvent): void => {
      const target = event.target as HTMLElement | null;
      if (!target) return;
      addBreadcrumb({
        type: 'click',
        data: {
          tag: target.tagName,
          id: target.id,
          className: text(target.className),
          text: text(target.textContent, 80)
        }
      });
    };
    document.addEventListener('click', clickHandler, true);
    cleanup.push(() => document.removeEventListener('click', clickHandler, true));
  }

  const recordRoute = (): void => addBreadcrumb({
    type: 'route',
    data: { url: sanitizeUrl(location.href) }
  });

  const originalPushState = history.pushState;
  const originalReplaceState = history.replaceState;
  history.pushState = function (data: unknown, unused: string, url?: string | URL | null): void {
    originalPushState.call(history, data, unused, url);
    recordRoute();
  };
  history.replaceState = function (data: unknown, unused: string, url?: string | URL | null): void {
    originalReplaceState.call(history, data, unused, url);
    recordRoute();
  };
  window.addEventListener('popstate', recordRoute);
  cleanup.push(() => {
    history.pushState = originalPushState;
    history.replaceState = originalReplaceState;
    window.removeEventListener('popstate', recordRoute);
  });

  if (options.captureFetch !== false && typeof window.fetch === 'function') {
    const originalFetch = window.fetch.bind(window);

    window.fetch = async (...args: Parameters<typeof fetch>): Promise<Response> => {
      const url = requestUrl(args[0]);
      if (isIngestUrl(url, ingestPrefix)) {
        return originalFetch(...args);
      }

      const startedAt = performance.now();
      try {
        const response = await originalFetch(...args);
        const duration = performance.now() - startedAt;
        const method = args[1]?.method || (args[0] instanceof Request ? args[0].method : 'GET');
        addBreadcrumb({
          type: 'fetch',
          data: { method, url, status: response.status, duration: Math.round(duration) }
        });
        client.captureBehavior('api', {
          transport: 'fetch',
          method,
          url,
          status: response.status,
          duration
        });

        if (response.status >= 500) {
          client.capture({
            eventType: 'ERROR',
            data: {
              name: 'HttpError',
              message: `HTTP ${response.status} ${url}`,
              file: url,
              status: response.status,
              duration,
              breadcrumbs: breadcrumbSnapshot()
            }
          });
        }
        return response;
      } catch (error) {
        const duration = performance.now() - startedAt;
        client.capture({
          eventType: 'ERROR',
          data: {
            name: error instanceof Error ? error.name : 'FetchError',
            message: error instanceof Error ? error.message : text(error),
            file: url,
            duration,
            breadcrumbs: breadcrumbSnapshot()
          }
        });
        throw error;
      }
    };

    cleanup.push(() => {
      window.fetch = originalFetch;
    });
  }

  if (options.captureXhr !== false && typeof XMLHttpRequest !== 'undefined') {
    const originalOpen = XMLHttpRequest.prototype.open;
    const originalSend = XMLHttpRequest.prototype.send;
    const meta = new WeakMap<XMLHttpRequest, { method: string; url: string; startedAt?: number }>();

    XMLHttpRequest.prototype.open = function (
      this: XMLHttpRequest,
      method: string,
      url: string | URL,
      ...rest: any[]
    ): void {
      meta.set(this, { method: method.toUpperCase(), url: sanitizeUrl(String(url)) });
      (originalOpen as any).call(this, method, url, ...rest);
    } as typeof XMLHttpRequest.prototype.open;

    XMLHttpRequest.prototype.send = function (
      this: XMLHttpRequest,
      body?: Document | XMLHttpRequestBodyInit | null
    ): void {
      const item = meta.get(this);
      if (!item || isIngestUrl(item.url, ingestPrefix)) {
        originalSend.call(this, body ?? null);
        return;
      }

      item.startedAt = performance.now();
      this.addEventListener('loadend', () => {
        const duration = performance.now() - (item.startedAt ?? performance.now());
        addBreadcrumb({
          type: 'xhr',
          data: {
            method: item.method,
            url: item.url,
            status: this.status,
            duration: Math.round(duration)
          }
        });
        client.captureBehavior('api', {
          transport: 'xhr',
          method: item.method,
          url: item.url,
          status: this.status,
          duration
        });

        if (this.status === 0 || this.status >= 500) {
          client.capture({
            eventType: 'ERROR',
            data: {
              name: this.status === 0 ? 'XhrNetworkError' : 'HttpError',
              message: this.status === 0
                ? `XHR network error ${item.url}`
                : `HTTP ${this.status} ${item.url}`,
              file: item.url,
              status: this.status,
              duration,
              breadcrumbs: breadcrumbSnapshot()
            }
          });
        }
      }, { once: true });

      originalSend.call(this, body ?? null);
    } as typeof XMLHttpRequest.prototype.send;

    cleanup.push(() => {
      XMLHttpRequest.prototype.open = originalOpen;
      XMLHttpRequest.prototype.send = originalSend;
    });
  }

  if (options.captureWhiteScreen !== false) {
    const timer = window.setTimeout(() => {
      const points = [
        [window.innerWidth / 2, window.innerHeight / 2],
        [window.innerWidth / 4, window.innerHeight / 4],
        [window.innerWidth * 3 / 4, window.innerHeight / 4],
        [window.innerWidth / 4, window.innerHeight * 3 / 4],
        [window.innerWidth * 3 / 4, window.innerHeight * 3 / 4]
      ];
      const emptyTags = new Set(['HTML', 'BODY']);
      const emptyCount = points.reduce((count, [x, y]) => {
        const node = document.elementFromPoint(x, y);
        return count + (!node || emptyTags.has(node.tagName) ? 1 : 0);
      }, 0);
      if (emptyCount >= 4) {
        client.capture({
          eventType: 'ERROR',
          data: {
            name: 'WhiteScreenError',
            message: 'page white screen detected',
            emptyPoints: emptyCount,
            breadcrumbs: breadcrumbSnapshot()
          }
        });
      }
    }, options.whiteScreenDelay ?? 3000);
    cleanup.push(() => window.clearTimeout(timer));
  }

  if (options.capturePerformance !== false && typeof PerformanceObserver !== 'undefined') {
    const observe = (entryType: string, handler: (entry: PerformanceEntry) => void): void => {
      if (!PerformanceObserver.supportedEntryTypes?.includes(entryType)) return;
      const observer = new PerformanceObserver(list => {
        list.getEntries().forEach(handler);
      });
      observer.observe({ type: entryType, buffered: true } as PerformanceObserverInit);
      observers.push(observer);
    };

    observe('paint', entry => {
      if (entry.name === 'first-paint') {
        client.capturePerformance('FP', entry.startTime);
      }
      if (entry.name === 'first-contentful-paint') {
        client.capturePerformance('FCP', entry.startTime);
      }
    });

    observe('largest-contentful-paint', entry => {
      client.capturePerformance('LCP', entry.startTime);
    });

    observe('longtask', entry => {
      client.capturePerformance('LongTask', entry.duration);
    });

    let inp = 0;
    observe('event', entry => {
      const timing = entry as PerformanceEntry & { interactionId?: number; duration?: number };
      if ((timing.interactionId ?? 0) > 0 && (timing.duration ?? 0) > inp) {
        inp = timing.duration ?? 0;
        client.capturePerformance('INP', inp);
      }
    });

    let cls = 0;
    observe('layout-shift', entry => {
      const shift = entry as PerformanceEntry & { value?: number; hadRecentInput?: boolean };
      if (!shift.hadRecentInput) {
        cls += shift.value ?? 0;
        client.capturePerformance('CLS', cls);
      }
    });

    if (options.captureResourceTiming !== false) {
      observe('resource', entry => {
        const resource = entry as PerformanceResourceTiming;
        if (isIngestUrl(resource.name, ingestPrefix)) return;
        client.capturePerformance('ResourceDuration', resource.duration, {
          resource: sanitizeUrl(resource.name),
          initiatorType: resource.initiatorType,
          transferSize: resource.transferSize,
          encodedBodySize: resource.encodedBodySize
        });
      });
    }

    if (options.captureNavigation !== false) {
      const navigation = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined;
      if (navigation) {
        client.capturePerformance('TTFB', navigation.responseStart - navigation.requestStart);
        client.capturePerformance('DOMContentLoaded',
          navigation.domContentLoadedEventEnd - navigation.startTime);
        client.capturePerformance('Load',
          navigation.loadEventEnd - navigation.startTime);
      }
    }

    cleanup.push(() => observers.forEach(observer => observer.disconnect()));
  }

  const onlineHandler = (): void => {
    void client.flush().catch(() => undefined);
  };
  window.addEventListener('online', onlineHandler);
  cleanup.push(() => window.removeEventListener('online', onlineHandler));

  const pageHideHandler = (): void => {
    void client.flush(true).catch(() => undefined);
  };
  window.addEventListener('pagehide', pageHideHandler);
  cleanup.push(() => window.removeEventListener('pagehide', pageHideHandler));

  return {
    client,
    breadcrumbs,
    async destroy(): Promise<void> {
      cleanup.forEach(fn => fn());
      await client.close().catch(() => undefined);
    }
  };
}

export type { MonitorClientOptions, MonitorEventEnvelope };
