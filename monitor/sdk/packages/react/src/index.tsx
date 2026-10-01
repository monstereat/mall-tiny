import React from 'react';
import type { MonitorClient } from '@observe/core';

export interface ObserveErrorBoundaryProps {
  client: MonitorClient;
  fallback?: React.ReactNode | ((error: Error) => React.ReactNode);
  children?: React.ReactNode;
}

interface State {
  error?: Error;
}

export class ObserveErrorBoundary extends React.Component<ObserveErrorBoundaryProps, State> {
  state: State = {};

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo): void {
    this.props.client.capture({
      eventType: 'ERROR',
      data: {
        name: error.name,
        message: error.message,
        stack: error.stack,
        componentStack: info.componentStack
      }
    });
  }

  render(): React.ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;
    if (typeof this.props.fallback === 'function') {
      return this.props.fallback(error);
    }
    return this.props.fallback ?? null;
  }
}
