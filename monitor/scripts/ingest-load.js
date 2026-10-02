import http from 'k6/http';
import { check } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const projectId = __ENV.PROJECT_ID || 'demo-web';
const ingestKey = __ENV.INGEST_KEY || 'dev-monitor-key';
const rate = Number(__ENV.RATE || 50);
const duration = __ENV.DURATION || '1m';

export const options = {
  scenarios: {
    ingest: {
      executor: 'constant-arrival-rate',
      rate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: Math.max(10, Math.ceil(rate / 2)),
      maxVUs: Math.max(50, rate * 2)
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500'],
  },
};

export default function () {
  const event = {
    eventId: `load-${__VU}-${__ITER}-${Date.now()}`,
    projectId,
    eventType: 'PERFORMANCE',
    timestamp: Date.now(),
    sessionId: `load-session-${__VU}`,
    userId: `load-user-${__VU}`,
    release: 'load-test',
    environment: 'load-test',
    pageUrl: 'http://localhost/load-test',
    sdkVersion: 'k6-load-test',
    device: {},
    data: { metric: 'LCP', value: 1234 },
  };
  const response = http.post(
    `${baseUrl}/api/v1/envelope`,
    JSON.stringify(event),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-Monitor-Key': ingestKey,
      },
    },
  );
  check(response, { 'ingest accepted': (res) => res.status === 200 });
}
