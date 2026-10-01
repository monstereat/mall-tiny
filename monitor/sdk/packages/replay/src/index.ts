import { record, type eventWithTime } from 'rrweb';
import type { MonitorClient } from '@observe/core';

export interface ReplayOptions {
  flushInterval?: number;
  maxEvents?: number;
  maskAllInputs?: boolean;
}

export interface ReplayController {
  flush(): void;
  stop(): void;
}

export function startReplay(client: MonitorClient, options: ReplayOptions = {}): ReplayController {
  const events: eventWithTime[] = [];
  const maxEvents = Math.max(50, options.maxEvents ?? 1000);
  const stopRecording = record({
    emit(event) {
      events.push(event);
      if (events.length > maxEvents) events.splice(0, events.length - maxEvents);
    },
    maskAllInputs: options.maskAllInputs ?? true,
    maskTextSelector: '[data-monitor-mask]',
    blockSelector: '[data-monitor-block]'
  });

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

  const timer = window.setInterval(flush, options.flushInterval ?? 15000);
  return {
    flush,
    stop(): void {
      window.clearInterval(timer);
      stopRecording?.();
      flush();
    }
  };
}
