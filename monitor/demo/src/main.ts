import { createApp } from 'vue';
import App from './App.vue';
import { init } from '@observe/browser';
import { installVueErrorHandler } from '@observe/vue';
import { startReplay } from '@observe/replay';

const endpoint = 'http://localhost:8080/api/v1/envelope';

const monitor = init({
  endpoint,
  tracePropagation: [new URL(endpoint).origin],
  projectId: 'demo-web',
  ingestKey: 'dev-monitor-key',
  release: 'v1.0.0',
  environment: 'development',
  batchSize: 10,
  flushInterval: 3000,
  captureErrors: true,
  capturePerformance: true,
  captureFetch: true,
  captureClicks: true
});

const app = createApp(App);
installVueErrorHandler(app, monitor.client, () => monitor.breadcrumbs);
const replay = startReplay(monitor.client, {
  flushInterval: 10000,
  maskAllInputs: true
});

window.addEventListener('beforeunload', () => {
  replay.stop();
});

app.mount('#app');
