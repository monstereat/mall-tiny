import { record, type eventWithTime } from 'rrweb';
import type { MonitorClient } from '@observe/core';

export interface ReplayOptions {
  flushInterval?: number;
  maxEvents?: number;
  maskAllInputs?: boolean;
  /** Sampling rate for sessions outside high-risk routes. Defaults to 1. */
  sampleRate?: number;
  /** Path prefixes that use the high-risk sampling rate while Replay is running. */
  highRiskRoutePrefixes?: string[];
  /** Sampling rate for high-risk routes. Defaults to 1 and cannot lower sampleRate. */
  highRiskSampleRate?: number;
}

export interface ReplayController {
  flush(): void;
  stop(): void;
}

function clampRate(value: number | undefined, fallback = 1): number {
  if (value == null) return fallback;
  return Math.max(0, Math.min(1, value));
}

function matchesRoutePrefix(pathname: string, prefix: string): boolean {
  const trimmedPrefix = prefix.trim();
  if (trimmedPrefix === '/') return pathname.startsWith('/');
  if (!trimmedPrefix) return false;
  const normalizedPrefix = trimmedPrefix.replace(/\/+$/, '');
  return pathname === normalizedPrefix || pathname.startsWith(`${normalizedPrefix}/`);
}

export function startReplay(client: MonitorClient, options: ReplayOptions = {}): ReplayController {
  const normalSampleRate = clampRate(options.sampleRate);
  const events: eventWithTime[] = [];
  const maxEvents = Math.max(50, options.maxEvents ?? 1000);
  let stopRecording: (() => void) | undefined;
  let stopped = false;

  const flush = (): void => {
    if (events.length === 0) return;
    const snapshot = events.splice(0, events.length);
    client.capture({
      eventType: 'REPLAY',
      data: {
        format: 'rrweb',
        events: snapshot
      }
    });
  };

  const shouldRecord = (pathname: string): boolean => {
    const highRiskRoute = options.highRiskRoutePrefixes?.some(prefix => matchesRoutePrefix(pathname, prefix)) ?? false;
    const sampleRate = highRiskRoute
      ? Math.max(normalSampleRate, clampRate(options.highRiskSampleRate))
      : normalSampleRate;
    return Math.random() < sampleRate;
  };

  const startRecording = (): void => {
    stopRecording = record({
      emit(event) {
        events.push(event);
        if (events.length > maxEvents) events.splice(0, events.length - maxEvents);
      },
      maskAllInputs: options.maskAllInputs ?? true,
      maskTextSelector: '[data-monitor-mask]',
      blockSelector: '[data-monitor-block]'
    });
  };

  let currentPathname = location.pathname;
  const handleRouteChange = (): void => {
    if (stopped || location.pathname === currentPathname) return;
    currentPathname = location.pathname;

    stopRecording?.();
    stopRecording = undefined;
    flush();

    if (shouldRecord(currentPathname)) startRecording();
  };

  const originalPushState = history.pushState;
  const originalReplaceState = history.replaceState;
  const wrappedPushState: History['pushState'] = function (this: History, ...args: Parameters<History['pushState']>) {
    const result = originalPushState.apply(this, args);
    handleRouteChange();
    return result;
  };
  const wrappedReplaceState: History['replaceState'] = function (this: History, ...args: Parameters<History['replaceState']>) {
    const result = originalReplaceState.apply(this, args);
    handleRouteChange();
    return result;
  };

  history.pushState = wrappedPushState;
  history.replaceState = wrappedReplaceState;
  window.addEventListener('popstate', handleRouteChange);

  if (shouldRecord(currentPathname)) startRecording();

  const timer = window.setInterval(flush, options.flushInterval ?? 15000);
  return {
    flush,
    stop(): void {
      if (stopped) return;
      stopped = true;
      window.clearInterval(timer);
      window.removeEventListener('popstate', handleRouteChange);
      history.pushState = originalPushState;
      history.replaceState = originalReplaceState;
      stopRecording?.();
      stopRecording = undefined;
      flush();
    }
  };
}
