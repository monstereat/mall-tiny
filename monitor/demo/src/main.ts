import { createApp } from 'vue';
import App from './App.vue';
import { init } from '@observe/browser';
import { installVueErrorHandler } from '@observe/vue';
import { startReplay } from '@observe/replay';

const endpoint = 'http://localhost:8080/api/v1/envelope';
const replayErrorBufferTest = new URLSearchParams(location.search).get('replayErrorBuffer') === '1';
const replaySampleRate = new URLSearchParams(location.search).get('replaySampleRate');
const replayMaskSelector = new URLSearchParams(location.search).get('replayMaskSelector');
const replayBlockSelector = new URLSearchParams(location.search).get('replayBlockSelector');
const memoryProfileRunId = new URLSearchParams(location.search).get('memoryProfile');
const issueRegressionRunId = new URLSearchParams(location.search).get('issueRegression');
const memoryProfileTest = memoryProfileRunId != null;

const monitor = init({
  endpoint,
  tracePropagation: [new URL(endpoint).origin],
  projectId: 'demo-web',
  ingestKey: 'dev-monitor-key',
  release: new URLSearchParams(location.search).get('release') ?? 'v1.0.0',
  environment: 'development',
  batchSize: 10,
  flushInterval: 3000,
  userId: memoryProfileTest
    ? `memory-profile-e2e-${memoryProfileRunId}`
    : issueRegressionRunId != null ? `issue-regression-e2e-${issueRegressionRunId}` : undefined,
  profileMemorySampleRate: memoryProfileTest ? 1 : undefined,
  profileMemoryIntervalMs: memoryProfileTest ? 10_000 : undefined,
  captureErrors: true,
  capturePerformance: true,
  captureFetch: true,
  captureClicks: true
});

const app = createApp(App);
installVueErrorHandler(app, monitor.client, () => monitor.breadcrumbs);
const replay = startReplay(monitor.client, {
  flushInterval: 10000,
  maskAllInputs: true,
  maskTextSelector: replayMaskSelector ?? undefined,
  blockSelector: replayBlockSelector ?? undefined,
  sampleRate: replaySampleRate == null ? undefined : Number(replaySampleRate),
  retainOnError: replayErrorBufferTest
});

window.addEventListener('beforeunload', () => {
  replay.stop();
});

app.provide('monitorReplayErrorBufferTest', replayErrorBufferTest);
app.mount('#app');
