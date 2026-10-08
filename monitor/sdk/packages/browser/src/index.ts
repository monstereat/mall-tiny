import {
  MonitorClient,
  type MonitorClientOptions,
  type MonitorEventEnvelope
} from '@observe/core';
import { resourceTimingDetails } from './resource-timing.js';
import { isWhiteScreen } from './white-screen.js';
import { subscribeWebVitals } from './web-vitals.js';

export interface BrowserMonitorOptions extends MonitorClientOptions {
  captureErrors?: boolean;
  capturePerformance?: boolean;
  captureFetch?: boolean;
  captureXhr?: boolean;
  captureClicks?: boolean;
  captureWhiteScreen?: boolean;
  captureResourceTiming?: boolean;
  captureNavigation?: boolean;
  capturePageViews?: boolean;
  capturePageDwell?: boolean;
  tracePropagation?: 'same-origin' | 'all' | string[];
  whiteScreenDelay?: number;
  whiteScreenConfirmations?: number;
  whiteScreenRootSelectors?: string[];
  whiteScreenSkeletonSelectors?: string[];
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
  'session', 'sessionid', 'code', 'secret', 'state'
]);

const activeMonitors = new WeakMap<object, {
  monitor: BrowserMonitor; projectId: string; endpoint: string; ingestKey: string;
}>();

function text(value: unknown, max = 200): string {
  const source = value == null ? '' : String(value);
  return source.length <= max ? source : source.slice(0, max);
}

function sanitizeUrl(raw: string): string {
  try {
    const url = new URL(raw, location.href);
    url.username = '';
    url.password = '';
    for (const key of [...url.searchParams.keys()]) {
      if (SENSITIVE_QUERY_KEYS.has(key.toLowerCase())) {
        url.searchParams.set(key, '[redacted]');
      }
    }
    url.hash = '';
    return url.toString();
  } catch {
    return raw.split('#')[0];
  }
}

function sanitizeRouteUrl(raw: string): string {
  try {
    const url = new URL(raw, location.href);
    url.username = '';
    url.password = '';
    url.search = '';
    const hash = url.hash.slice(1);
    const isHashRoute = hash.startsWith('/') || hash.startsWith('!/');
    url.hash = isHashRoute ? hash.split('?', 1)[0] : '';
    return url.toString();
  } catch {
    return sanitizeUrl(raw);
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

function newTraceId(): string {
  return [...crypto.getRandomValues(new Uint8Array(16))]
    .map(value => value.toString(16).padStart(2, '0')).join('');
}

function parseTraceparent(value: string | null): { traceId: string; spanId: string } | undefined {
  const match = value?.trim().match(/^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$/i);
  if (!match || /^0+$/.test(match[1]) || /^0+$/.test(match[2])) return undefined;
  return { traceId: match[1].toLowerCase(), spanId: match[2].toLowerCase() };
}

function matchingResponseTraceparent(value: string | null, traceId?: string): string | undefined {
  const match = value?.trim().match(/^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$/i);
  if (!match || !traceId
      || match[1].toLowerCase() !== traceId.toLowerCase()
      || /^0+$/.test(match[1])
      || /^0+$/.test(match[2])) {
    return undefined;
  }
  return value?.trim().toLowerCase();
}

function newSpanId(): string {
  return [...crypto.getRandomValues(new Uint8Array(8))]
    .map(value => value.toString(16).padStart(2, '0')).join('');
}

function newTraceparent(traceId: string, spanId: string): string {
  return `00-${traceId}-${spanId}-01`;
}

function captureHttpSpan(
  client: MonitorClient,
  details: {
    traceId?: string;
    parentSpanId?: string;
    spanId: string;
    method: string;
    url: string;
    startTime: number;
    durationMs: number;
    statusCode?: number;
    networkError?: boolean;
  },
  traceparent?: string
): void {
  const failed = details.networkError === true || (details.statusCode ?? 0) >= 500;
  client.capture({
    eventType: 'SPAN',
    traceId: details.traceId,
    timestamp: details.startTime + details.durationMs,
    pageUrl: sanitizeUrl(location.href),
    data: {
      spanId: details.spanId,
      parentSpanId: details.parentSpanId,
      op: 'http.client',
      description: text(`${details.method} ${details.url}`, 200),
      startTime: details.startTime,
      durationMs: Math.max(0, details.durationMs),
      status: failed ? 'error' : 'ok',
      statusCode: details.statusCode
    }
  }, traceparent);
}

function canPropagateTrace(url: string, mode: BrowserMonitorOptions['tracePropagation']): boolean {
  if (mode === 'all') return true;
  try {
    const targetOrigin = new URL(url, location.href).origin;
    if (Array.isArray(mode)) {
      return mode.some(origin => {
        try {
          return new URL(origin).origin === targetOrigin;
        } catch {
          return false;
        }
      });
    }
    return targetOrigin === location.origin;
  } catch {
    return false;
  }
}

export function init(options: BrowserMonitorOptions): BrowserMonitor {
  const host = window;
  const activeMonitor = activeMonitors.get(host);
  if (activeMonitor) {
    if (activeMonitor.projectId !== options.projectId || activeMonitor.endpoint !== options.endpoint
        || activeMonitor.ingestKey !== options.ingestKey) {
      throw new Error('Destroy the active monitor before changing its project, endpoint or credentials');
    }
    return activeMonitor.monitor;
  }

  const client = new MonitorClient(options);
  const breadcrumbs: Breadcrumb[] = [];
  const maxBreadcrumbs = Math.max(1, options.maxBreadcrumbs ?? 30);
  const cleanup: Array<() => void> = [];
  const observers: PerformanceObserver[] = [];
  const ingestPrefix = options.endpoint.replace(/\/$/, '');
  let destroyed = false;
  let lastTraceId: string | undefined;
  let currentPageUrl = sanitizeRouteUrl(location.href);
  let pageViewId: string | undefined;
  let visibleSince: number | undefined;
  const trackPages = options.capturePageViews === true || options.capturePageDwell === true;
  const onRouteChange: { current: (url: string) => void } = { current: () => undefined };

  const now = (): number => typeof performance !== 'undefined' && typeof performance.now === 'function'
    ? performance.now()
    : Date.now();

  const flushVisibleDwell = (): void => {
    if (visibleSince === undefined) return;
    const durationMs = Math.max(0, now() - visibleSince);
    visibleSince = undefined;
    if (options.capturePageDwell === true && pageViewId && durationMs > 0) {
      client.capturePageDwell(pageViewId, currentPageUrl, durationMs);
    }
  };

  const startPage = (url: string, referrer?: string): void => {
    currentPageUrl = url;
    pageViewId = trackPages ? client.page({ url, referrer }) ?? undefined : undefined;
    if (options.capturePageDwell === true && pageViewId && document.visibilityState !== 'hidden') {
      visibleSince = now();
    } else {
      visibleSince = undefined;
    }
  };

  if (trackPages) {
    const referrer = document.referrer ? sanitizeRouteUrl(document.referrer) : undefined;
    startPage(currentPageUrl, referrer);
  }

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
          traceId: lastTraceId,
          data: {
            name: event.error.name ?? 'Error',
            message: event.message,
            stack: event.error.stack,
            mechanism: 'onerror',
            unhandled: true,
            file: event.filename,
            line: event.lineno,
            column: event.colno,
            traceId: lastTraceId,
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
            mechanism: 'resource',
            unhandled: false,
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
        traceId: lastTraceId,
        data: {
          name: reason instanceof Error ? reason.name : 'UnhandledRejection',
          message: reason instanceof Error ? reason.message : text(reason),
          stack: reason instanceof Error ? reason.stack : undefined,
          mechanism: 'unhandledrejection',
          unhandled: true,
          traceId: lastTraceId,
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

  const recordRoute = (): void => {
    const nextUrl = sanitizeRouteUrl(location.href);
    if (nextUrl === currentPageUrl) return;
    const previousUrl = currentPageUrl;
    flushVisibleDwell();
    addBreadcrumb({ type: 'route', data: { url: nextUrl } });
    if (trackPages) startPage(nextUrl, previousUrl);
    else currentPageUrl = nextUrl;
    onRouteChange.current(nextUrl);
  };

  const originalPushState = history.pushState;
  const originalReplaceState = history.replaceState;
  const wrappedPushState: History['pushState'] = function (data: unknown, unused: string, url?: string | URL | null): void {
    originalPushState.call(history, data, unused, url);
    recordRoute();
  };
  const wrappedReplaceState: History['replaceState'] = function (data: unknown, unused: string, url?: string | URL | null): void {
    originalReplaceState.call(history, data, unused, url);
    recordRoute();
  };
  history.pushState = wrappedPushState;
  history.replaceState = wrappedReplaceState;
  window.addEventListener('popstate', recordRoute);
  window.addEventListener('hashchange', recordRoute);
  cleanup.push(() => {
    if (history.pushState === wrappedPushState) history.pushState = originalPushState;
    if (history.replaceState === wrappedReplaceState) history.replaceState = originalReplaceState;
    window.removeEventListener('popstate', recordRoute);
    window.removeEventListener('hashchange', recordRoute);
  });

  if (options.capturePageDwell === true) {
    const visibilityHandler = (): void => {
      if (document.visibilityState === 'hidden') {
        flushVisibleDwell();
      } else if (pageViewId && visibleSince === undefined) {
        visibleSince = now();
      }
    };
    const pageHideDwellHandler = (): void => flushVisibleDwell();
    const pageShowHandler = (event: PageTransitionEvent): void => {
      if (event.persisted && trackPages) {
        flushVisibleDwell();
        startPage(currentPageUrl, currentPageUrl);
      } else if (pageViewId && document.visibilityState !== 'hidden' && visibleSince === undefined) {
        visibleSince = now();
      }
    };
    document.addEventListener('visibilitychange', visibilityHandler);
    window.addEventListener('pagehide', pageHideDwellHandler);
    window.addEventListener('pageshow', pageShowHandler);
    cleanup.push(() => {
      flushVisibleDwell();
      document.removeEventListener('visibilitychange', visibilityHandler);
      window.removeEventListener('pagehide', pageHideDwellHandler);
      window.removeEventListener('pageshow', pageShowHandler);
    });
  }

  if (options.captureFetch !== false && typeof window.fetch === 'function') {
    const originalFetch = window.fetch.bind(window);

    window.fetch = async (...args: Parameters<typeof fetch>): Promise<Response> => {
      const url = requestUrl(args[0]);
      if (isIngestUrl(url, ingestPrefix)) {
        return originalFetch(...args);
      }

      const requestHeaders = new Headers(args[0] instanceof Request ? args[0].headers : undefined);
      new Headers(args[1]?.headers).forEach((value, name) => requestHeaders.set(name, value));
      const existingParent = requestHeaders.get('traceparent');
      const hasExistingParent = requestHeaders.has('traceparent');
      const propagateTrace = canPropagateTrace(url, options.tracePropagation);
      const inheritedParent = parseTraceparent(existingParent);
      const traceId = inheritedParent?.traceId ?? newTraceId();
      const spanId = newSpanId();
      const parentSpanId = inheritedParent?.spanId;
      lastTraceId = traceId;
      if (inheritedParent && propagateTrace) {
        requestHeaders.set('traceparent', newTraceparent(traceId, spanId));
      } else if (propagateTrace && !hasExistingParent) {
        requestHeaders.set('traceparent', newTraceparent(traceId!, spanId));
      }
      const startTime = Date.now();
      const startedAt = performance.now();
      try {
        const response = await originalFetch(args[0], { ...args[1], headers: requestHeaders });
        const responseTraceparent = matchingResponseTraceparent(
          response.headers.get('traceparent'),
          traceId
        );
        const duration = performance.now() - startedAt;
        const method = args[1]?.method || (args[0] instanceof Request ? args[0].method : 'GET');
        captureHttpSpan(client, {
          traceId,
          parentSpanId,
          spanId,
          method: text(method.toUpperCase(), 16),
          url,
          startTime,
          durationMs: duration,
          statusCode: response.status
        }, responseTraceparent);
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
        }, traceId, responseTraceparent);

        if (response.status >= 500) {
          client.capture({
            eventType: 'ERROR',
            traceId,
            data: {
              name: 'HttpError',
              message: `HTTP ${response.status} ${url}`,
              file: url,
              status: response.status,
              duration,
              traceId,
              breadcrumbs: breadcrumbSnapshot()
            }
          }, responseTraceparent);
        }
        return response;
      } catch (error) {
        const duration = performance.now() - startedAt;
        captureHttpSpan(client, {
          traceId,
          parentSpanId,
          spanId,
          method: text((args[1]?.method || (args[0] instanceof Request ? args[0].method : 'GET')).toUpperCase(), 16),
          url,
          startTime,
          durationMs: duration,
          networkError: true
        });
        client.capture({
          eventType: 'ERROR',
          traceId,
          data: {
            name: error instanceof Error ? error.name : 'FetchError',
            message: error instanceof Error ? error.message : text(error),
            file: url,
            duration,
            traceId,
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
    const originalSetRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
    const meta = new WeakMap<XMLHttpRequest, {
      method: string; url: string; startedAt?: number; startTime?: number;
      traceId?: string; parentSpanId?: string; spanId?: string
    }>();
    const suppliedTraceparent = new WeakMap<XMLHttpRequest, string>();

    XMLHttpRequest.prototype.setRequestHeader = function (this: XMLHttpRequest, name: string, value: string): void {
      if (name.toLowerCase() === 'traceparent') {
        const previous = suppliedTraceparent.get(this);
        suppliedTraceparent.set(this, previous ? `${previous}, ${value}` : value);
        return;
      }
      originalSetRequestHeader.call(this, name, value);
    };

    XMLHttpRequest.prototype.open = function (
      this: XMLHttpRequest,
      method: string,
      url: string | URL,
      ...rest: any[]
    ): void {
      suppliedTraceparent.delete(this);
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
      item.startTime = Date.now();
      const existingParent = suppliedTraceparent.get(this);
      const hasExistingParent = suppliedTraceparent.has(this);
      const propagateTrace = canPropagateTrace(item.url, options.tracePropagation);
      const inheritedParent = parseTraceparent(existingParent ?? null);
      item.traceId = inheritedParent?.traceId ?? newTraceId();
      item.parentSpanId = inheritedParent?.spanId;
      item.spanId = newSpanId();
      lastTraceId = item.traceId;
      if (inheritedParent && propagateTrace) {
        originalSetRequestHeader.call(this, 'traceparent', newTraceparent(item.traceId!, item.spanId));
      } else if (!hasExistingParent && propagateTrace) {
        originalSetRequestHeader.call(this, 'traceparent', newTraceparent(item.traceId!, item.spanId));
      } else if (hasExistingParent) {
        originalSetRequestHeader.call(this, 'traceparent', existingParent!);
      }
      let completed = false;
      const completeSpan = (networkError = false): void => {
        if (completed) return;
        completed = true;
        const responseTraceparent = networkError || this.status === 0
          ? undefined
          : matchingResponseTraceparent(this.getResponseHeader('traceparent'), item.traceId);
        const duration = performance.now() - (item.startedAt ?? performance.now());
        captureHttpSpan(client, {
          traceId: item.traceId,
          parentSpanId: item.parentSpanId,
          spanId: item.spanId!,
          method: item.method,
          url: item.url,
          startTime: item.startTime ?? Date.now(),
          durationMs: duration,
          statusCode: networkError || this.status === 0 ? undefined : this.status,
          networkError: networkError || this.status === 0
        }, responseTraceparent);
      };
      this.addEventListener('loadend', () => {
        completeSpan(this.status === 0);
        const responseTraceparent = matchingResponseTraceparent(
          this.getResponseHeader('traceparent'),
          item.traceId
        );
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
        }, item.traceId, responseTraceparent);

        if (this.status === 0 || this.status >= 500) {
          client.capture({
            eventType: 'ERROR',
            traceId: item.traceId,
            data: {
              name: this.status === 0 ? 'XhrNetworkError' : 'HttpError',
              message: this.status === 0
                ? `XHR network error ${item.url}`
                : `HTTP ${this.status} ${item.url}`,
              file: item.url,
              status: this.status,
              duration,
              traceId: item.traceId,
              breadcrumbs: breadcrumbSnapshot()
            }
          }, responseTraceparent);
        }
      }, { once: true });

      try {
        originalSend.call(this, body ?? null);
      } catch (error) {
        completeSpan(true);
        throw error;
      }
    } as typeof XMLHttpRequest.prototype.send;

    cleanup.push(() => {
      XMLHttpRequest.prototype.open = originalOpen;
      XMLHttpRequest.prototype.send = originalSend;
      XMLHttpRequest.prototype.setRequestHeader = originalSetRequestHeader;
    });
  }

  if (options.captureWhiteScreen !== false) {
    let timer: number | undefined;
    let checkId = 0;
    const requestedConfirmations = options.whiteScreenConfirmations ?? 2;
    const maxConfirmations = Number.isFinite(requestedConfirmations)
      ? Math.max(1, Math.min(5, Math.floor(requestedConfirmations)))
      : 2;
    const clearCheck = (): void => {
      if (timer !== undefined) window.clearTimeout(timer);
      timer = undefined;
    };
    const scheduleCheck = (): void => {
      clearCheck();
      const currentCheckId = ++checkId;
      let confirmations = 0;
      const check = (): void => {
        if (destroyed || currentCheckId !== checkId) return;
        const blank = isWhiteScreen(document, window.innerWidth, window.innerHeight, {
          rootSelectors: options.whiteScreenRootSelectors,
          skeletonSelectors: options.whiteScreenSkeletonSelectors
        });
        if (!blank) return;
        if (confirmations < maxConfirmations) {
          confirmations += 1;
          timer = window.setTimeout(check, 1000);
          return;
        }
        client.capture({
          eventType: 'ERROR',
          pageUrl: currentPageUrl,
          data: {
            name: 'WhiteScreenError',
            message: 'page white screen detected',
            checkPoints: 9,
            confirmations,
            breadcrumbs: breadcrumbSnapshot()
          }
        });
      };
      timer = window.setTimeout(check, options.whiteScreenDelay ?? 3000);
    };
    onRouteChange.current = scheduleCheck;
    scheduleCheck();
    cleanup.push(() => {
      checkId += 1;
      clearCheck();
      onRouteChange.current = () => undefined;
    });
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
    });

    cleanup.push(subscribeWebVitals(window, client, sanitizeRouteUrl, options.captureNavigation !== false));

    observe('longtask', entry => {
      client.capturePerformance('LongTask', entry.duration);
    });

    if (options.captureResourceTiming !== false) {
      observe('resource', entry => {
        const resource = entry as PerformanceResourceTiming;
        if (isIngestUrl(resource.name, ingestPrefix)) return;
        const timing = resourceTimingDetails(resource);
        const url = sanitizeUrl(resource.name);
        client.capturePerformance('ResourceDuration', resource.duration, {
          url,
          type: resource.initiatorType,
          resource: url,
          initiatorType: resource.initiatorType,
          durationMs: resource.duration,
          totalBytes: timing.transferSize,
          ...timing
        });
      });
    }

    if (options.captureNavigation !== false) {
      const navigation = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined;
      if (navigation) {
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

  const monitor: BrowserMonitor = {
    client,
    breadcrumbs,
    async destroy(): Promise<void> {
      if (destroyed) return;
      destroyed = true;
      cleanup.forEach(fn => {
        try {
          fn();
        } catch {
          // Cleanup must continue even if an integration hook has already been replaced.
        }
      });
      if (activeMonitors.get(host)?.monitor === monitor) activeMonitors.delete(host);
      await client.close().catch(() => undefined);
    }
  };
  activeMonitors.set(host, { monitor, projectId: options.projectId, endpoint: options.endpoint, ingestKey: options.ingestKey });
  return monitor;
}

export type { MonitorClientOptions, MonitorEventEnvelope };
