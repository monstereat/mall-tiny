import type { App, ComponentPublicInstance } from 'vue';
import type { MonitorClient } from '@observe/core';

export interface VueBreadcrumb {
  type: string;
  timestamp: number;
  data: Record<string, unknown>;
}

interface SourceLocation {
  file: string;
  line: number;
  column: number;
}

function firstAppFrame(stack?: string): SourceLocation | undefined {
  if (!stack) return undefined;

  for (const frame of stack.split('\n')) {
    const match = frame.match(/((?:https?|file|webpack|blob):[^\s()]+):(\d+):(\d+)\)?$/);
    if (!match) continue;

    const file = match[1];
    if (/(?:^|\/)node_modules(?:\/|$)|\/@vite\/|\/__vite_/.test(file)) continue;

    return { file, line: Number(match[2]), column: Number(match[3]) };
  }

  return undefined;
}

export function installVueErrorHandler(
  app: App,
  client: MonitorClient,
  getBreadcrumbs?: () => readonly VueBreadcrumb[]
): void {
  const previous = app.config.errorHandler;
  app.config.errorHandler = (
    error: unknown,
    instance: ComponentPublicInstance | null,
    info: string
  ): void => {
    const err = error instanceof Error ? error : new Error(String(error));
    const source = firstAppFrame(err.stack);
    client.capture({
      eventType: 'ERROR',
      data: {
        name: err.name || 'VueError',
        message: err.message,
        stack: err.stack,
        framework: 'vue',
        component: instance?.$options?.name ?? instance?.$options?.__name ?? 'anonymous',
        info,
        ...source,
        breadcrumbs: getBreadcrumbs?.().map(item => ({
          ...item,
          data: { ...item.data }
        })) ?? []
      }
    });
    previous?.(error, instance, info);
  };
}
