import { onCLS, onFCP, onINP, onLCP, onTTFB, type Metric } from 'web-vitals';
import type { MonitorClient } from '@observe/core';

type Reporter = (
  callback: (metric: Metric) => void,
  options?: { reportAllChanges?: boolean; reportSoftNavs?: boolean }
) => void;

export interface WebVitalsApi {
  onCLS: Reporter;
  onFCP: Reporter;
  onINP: Reporter;
  onLCP: Reporter;
  onTTFB: Reporter;
}

type CachedMetric = Pick<Metric, 'name' | 'value' | 'delta' | 'id' | 'navigationType' | 'navigationURL'>;
type CachedMetricResult = { metric: CachedMetric; metricUpdate: number };
type WebVitalsHandler = (metric: CachedMetric, metricUpdate: number) => void;

interface WebVitalsRegistry {
  handlers: Set<WebVitalsHandler>;
  latestMetrics: Map<Metric['name'], CachedMetricResult>;
  reportSequence: number;
  installed: boolean;
  sanitizeUrl: (url: string) => string;
}

const registries = new WeakMap<object, WebVitalsRegistry>();
const CORE_METRIC_NAMES = new Set<Metric['name']>(['CLS', 'FCP', 'INP', 'LCP', 'TTFB']);

const webVitalsApi: WebVitalsApi = { onCLS, onFCP, onINP, onLCP, onTTFB };

function install(registry: WebVitalsRegistry, api: WebVitalsApi): void {
  const report = (metric: Metric): void => {
    if (!CORE_METRIC_NAMES.has(metric.name) || !metric.id || !Number.isFinite(metric.value)) return;
    const metricUpdate = ++registry.reportSequence;
    const snapshot: CachedMetric = {
      name: metric.name,
      value: metric.value,
      delta: metric.delta,
      id: metric.id,
      navigationType: metric.navigationType,
      navigationURL: metric.navigationURL ? registry.sanitizeUrl(metric.navigationURL) : undefined
    };
    registry.latestMetrics.set(metric.name, { metric: snapshot, metricUpdate });
    for (const handler of registry.handlers) {
      try {
        handler(snapshot, metricUpdate);
      } catch {
        // A telemetry integration must not interrupt browser event processing.
      }
    }
  };
  api.onFCP(report);
  api.onTTFB(report);
  // The soft-enabled callback also reports the initial navigation and avoids a second hard-navigation metric stream.
  api.onCLS(report, { reportAllChanges: true, reportSoftNavs: true });
  api.onINP(report, { reportAllChanges: true, reportSoftNavs: true });
  api.onLCP(report, { reportAllChanges: true, reportSoftNavs: true });
  registry.installed = true;
}

export function subscribeWebVitals(
  host: object,
  client: MonitorClient,
  sanitizeUrl: (url: string) => string,
  captureNavigation = true,
  api: WebVitalsApi = webVitalsApi
): () => void {
  let registry = registries.get(host);
  const wasInstalled = registry?.installed === true;
  const handler: WebVitalsHandler = (metric, metricUpdate) => {
    if (metric.name === 'TTFB' && !captureNavigation) return;
    const navigationUrl = sanitizeUrl(metric.navigationURL || location.href);
    client.capture({
      eventType: 'PERFORMANCE',
      pageUrl: navigationUrl,
      data: {
        metric: metric.name,
        value: metric.value,
        delta: Number.isFinite(metric.delta) ? metric.delta : undefined,
        metricId: metric.id,
        metricUpdate,
        navigationType: metric.navigationType,
        navigationURL: navigationUrl
      }
    }, undefined, true);
  };
  if (!registry) {
    registry = {
      handlers: new Set(), latestMetrics: new Map(), reportSequence: 0,
      installed: false, sanitizeUrl
    };
    registries.set(host, registry);
  }
  registry.handlers.add(handler);
  if (!registry.installed) install(registry, api);
  else if (wasInstalled) {
    for (const { metric, metricUpdate } of registry.latestMetrics.values()) {
      handler(metric, metricUpdate);
    }
  }

  return () => registry?.handlers.delete(handler);
}
