import type { App, ComponentPublicInstance } from 'vue';
import type { MonitorClient } from '@observe/core';

export function installVueErrorHandler(app: App, client: MonitorClient): void {
  const previous = app.config.errorHandler;
  app.config.errorHandler = (
    error: unknown,
    instance: ComponentPublicInstance | null,
    info: string
  ): void => {
    const err = error instanceof Error ? error : new Error(String(error));
    client.capture({
      eventType: 'ERROR',
      data: {
        name: err.name || 'VueError',
        message: err.message,
        stack: err.stack,
        framework: 'vue',
        component: instance?.$options?.name ?? instance?.$options?.__name ?? 'anonymous',
        info
      }
    });
    previous?.(error, instance, info);
  };
}
