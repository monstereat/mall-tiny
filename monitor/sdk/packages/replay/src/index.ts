import { record, type eventWithTime } from 'rrweb';
import type { MonitorClient } from '@observe/core';

export interface ReplayOptions {
  flushInterval?: number;
  maxEvents?: number;
  maskAllInputs?: boolean;
  /** CSS selector for text to mask. Defaults to [data-monitor-mask]. */
  maskTextSelector?: string;
  /** CSS selector for content to block from Replay. Defaults to [data-monitor-block]. */
  blockSelector?: string;
  /** Keep an unsampled session in memory and upload it when an error is captured. Defaults to false. */
  retainOnError?: boolean;
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
  const retainOnError = options.retainOnError ?? false;
  let stopRecording: (() => void) | undefined;
  let postErrorTimer: number | undefined;
  let postErrorActive = false;
  let stopped = false;

  const flushBufferedEvents = (bypassSampling = false): void => {
    if (events.length === 0) return;
    const snapshot = events.splice(0, events.length);
    client.capture({
      eventType: 'REPLAY',
      data: {
        format: 'rrweb',
        events: snapshot
      }
    }, undefined, bypassSampling);
  };

  let currentPathname = location.pathname;
  let sampled = false;

  const trimBuffer = (): void => {
    const cutoff = Date.now() - 60_000;
    while (events.length > 0 && events[0].timestamp < cutoff) events.shift();
    if (events.length > maxEvents) events.splice(0, events.length - maxEvents);
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
        trimBuffer();
        events.push(event);
        if (events.length > maxEvents) events.splice(0, events.length - maxEvents);
      },
      maskAllInputs: options.maskAllInputs ?? true,
      maskTextSelector: options.maskTextSelector ?? '[data-monitor-mask]',
      blockSelector: options.blockSelector ?? '[data-monitor-block]'
    });
  };

  const handleError = (): void => {
    if (!retainOnError || sampled || stopped || postErrorActive) return;
    trimBuffer();
    postErrorActive = true;
    flushBufferedEvents(true);
    postErrorTimer = window.setTimeout(() => {
      postErrorTimer = undefined;
      flushBufferedEvents(true);
      postErrorActive = false;
    }, 30_000);
  };
  const unsubscribeError = retainOnError ? client.onError(handleError) : undefined;

  const handleRouteChange = (): void => {
    if (stopped || location.pathname === currentPathname) return;
    const previousSampled = sampled;
    currentPathname = location.pathname;

    stopRecording?.();
    stopRecording = undefined;
    if (previousSampled || postErrorActive) flushBufferedEvents(postErrorActive);
    else events.splice(0, events.length);

    sampled = shouldRecord(currentPathname);
    if (sampled || retainOnError) startRecording();
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

  sampled = shouldRecord(currentPathname);
  if (sampled || retainOnError) startRecording();

  const timer = window.setInterval(() => {
    if (sampled || postErrorActive) flushBufferedEvents(postErrorActive);
  }, options.flushInterval ?? 15000);
  return {
    flush: flushBufferedEvents,
    stop(): void {
      if (stopped) return;
      stopped = true;
      window.clearInterval(timer);
      if (postErrorTimer !== undefined) {
        window.clearTimeout(postErrorTimer);
        postErrorTimer = undefined;
      }
      unsubscribeError?.();
      window.removeEventListener('popstate', handleRouteChange);
      history.pushState = originalPushState;
      history.replaceState = originalReplaceState;
      stopRecording?.();
      stopRecording = undefined;
      if (sampled || postErrorActive) flushBufferedEvents(postErrorActive);
      else events.splice(0, events.length);
      postErrorActive = false;
    }
  };
}
