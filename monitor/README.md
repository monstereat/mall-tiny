# monitor

监控客户端相关代码统一放在本目录。

```text
monitor/
├── sdk/
│   └── packages/
│       ├── core       Event Protocol / Queue / Transport / Sampling
│       ├── browser    Error / API / Web Vitals / Breadcrumb / WhiteScreen
│       ├── vue        Vue errorHandler Adapter
│       ├── react      React ErrorBoundary Adapter
│       └── replay     rrweb Recorder
├── demo/              SDK Vue 联调应用
└── scripts/           Release / SourceMap 上传
```

构建：

```bash
cd sdk
pnpm install
pnpm build
```

Demo：

```bash
cd demo
pnpm install
pnpm dev
```

浏览器 SDK 默认通过 `/api/v1/envelope/batch` 批量上报。

## OTLP Server Spans

Server tracing can export OTLP/HTTP JSON to the project-scoped endpoint with the project's ingest key:

```bash
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT='http://localhost:8080/api/v1/otlp/your-project-key/v1/traces'
export OTEL_EXPORTER_OTLP_TRACES_PROTOCOL='http/protobuf'
export OTEL_EXPORTER_OTLP_TRACES_HEADERS='X-Monitor-Key=your-ingest-key'
```

The endpoint accepts OTLP/HTTP JSON (`application/json`) and binary protobuf (`application/x-protobuf`) `ExportTraceServiceRequest` payloads, up to 64 MiB per request. Standard OpenTelemetry Java exporters can use `http/protobuf` and point the traces endpoint here. Exporters that support OTLP/HTTP JSON can set the protocol to `http/json`. OTLP/gRPC is not supported; send it through an OpenTelemetry Collector configured for OTLP/HTTP. Compressed request bodies (`Content-Encoding: gzip`) are not supported. The endpoint maps approved service name/version/environment resource attributes, span name/kind/IDs/status, and integer HTTP status codes; other span and resource attributes are discarded before existing project ingest processing.

Accepted spans are stored in the project's `span_event` table and appear alongside Browser HTTP spans in the Admin Trace detail waterfall. The query returns the approved span fields plus `serviceName` and OTLP kind; it does not return arbitrary OTLP attributes or tags.

# Session Replay privacy

Replay 默认将所有输入值遮罩；文本遮罩和区域屏蔽分别使用 `[data-monitor-mask]`、`[data-monitor-block]`。应用可以传入自己的 CSS selector：

```ts
import { startReplay } from '@observe/replay';

startReplay(client, {
  maskTextSelector: '[data-private], .customer-email',
  blockSelector: '[data-secret-panel]'
});
```

被 `maskTextSelector` 命中的元素文本会被遮罩；`blockSelector` 命中的区域不会录入 Replay。未被遮罩或屏蔽的 DOM 文本仍会录制，因此应按应用页面内容标记个人信息和敏感区域。
# Application Metrics

`MonitorClient.recordMetric` publishes custom metric samples through the normal ingest, Kafka and ClickHouse pipeline. Supported sample types are `counter`, `gauge` and `distribution`:

```ts
client.recordMetric('checkout.duration_ms', 128, {
  metricType: 'distribution',
  unit: 'millisecond',
  tags: { flow: 'checkout', region: 'cn-east' },
  traceId
});
```

Metric names use letters, digits, `_`, `:`, `.`, or `-` (up to 128 characters). Each sample accepts at most 20 scalar dimensions; dimension keys are limited to 64 characters and values to 128. Samples are project-rate-limited like other telemetry and retained in ClickHouse for 90 days. The Admin Metrics and Explore pages query these samples, and alert rules can use a custom metric name.

# Release Health

The Browser SDK records a `session/start` marker by default, independent of `sampleRate`, so Release Health uses an unsampled session denominator. Changing the user with `client.setUser()` ends the current session and starts a new one, keeping each session associated with one user identity. Set `releaseHealth: false` to disable lifecycle markers. Global JavaScript errors and unhandled promise rejections count as unhandled errors; resource load errors and explicit `captureException()` calls do not.

The Admin **Releases** page shows sessions, crash-free sessions, crash-free users, and unhandled errors for sessions started in the selected 24-hour, 7-day, or 30-day window. User-scoped data deletion also removes telemetry and the session marker for matching sessions inside the selected deletion range.

# Server-side credential scrubbing

The ingest service filters common credential fields (`password`, `authorization`, `cookie`, API keys, and access/refresh tokens) from telemetry data, device context, and nested values before cardinality accounting or Kafka publishing. It also filters those values from URLs, including query/fragment parameters and URL user-info, and removes common `Bearer`/`Basic` credentials embedded in text. Project Owners can opt into filtering email addresses, Luhn-validated payment card numbers, and valid IPv4/IPv6 addresses in **Data Governance**; the policy is applied to new events of every signal type before cardinality accounting or storage. AI provider requests and returned suggestions always scrub payment card numbers and IP addresses even when the project's ingestion filters are disabled. Existing stored events are not rewritten. `userId` and `sessionId` remain intact for issue impact, release health, and user-scoped deletion. Custom regexes and broader PII categories are not supported yet.

# Cron Monitors

Create a monitor in the Admin **Crons** page. Schedules can use an interval such as `5m` or a five/six-field crontab with an IANA timezone. A job reports its start, then reports `ok` or `error` with the same check-in ID:

```bash
export MONITOR_URL='http://localhost:8080'
export PROJECT_KEY='your-project-key'
export MONITOR_INGEST_KEY='your-ingest-key'
export CHECKIN_ID="$(uuidgen)"

curl -fsS -X POST "$MONITOR_URL/api/v1/monitors/$PROJECT_KEY/daily-report/check-ins" \
  -H "X-Monitor-Key: $MONITOR_INGEST_KEY" \
  -H 'Content-Type: application/json' \
  -d "{\"checkinId\":\"$CHECKIN_ID\",\"status\":\"in_progress\"}"

# Run the job here, then report its result.
curl -fsS -X PUT "$MONITOR_URL/api/v1/monitors/$PROJECT_KEY/daily-report/check-ins/$CHECKIN_ID" \
  -H "X-Monitor-Key: $MONITOR_INGEST_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"status":"ok"}'
```

Use `"status":"error"` on failure. Check-in IDs are idempotent; the monitor evaluates missing check-ins and jobs that exceed the configured maximum runtime. The Crons page shows recent check-in history and current health.

To alert when any active Cron monitor is unhealthy, create an Alert rule using metric `cron_unhealthy_count`, operator `>=`, and threshold `1`. The rule uses the shared silence, recovery, and Webhook delivery flow. It evaluates the project-wide count of Cron monitors in `warning` or `error` health states; it returns to zero after the monitors recover or are disabled/deleted.

To receive notifications when an Issue is first created or regresses after resolution, create separate Alert rules with metrics `new_issue` and `issue_regression`, and attach the desired tenant notification route or Webhook. These are event notifications: threshold, window, duration, and cooldown do not apply. Project, rule, and Issue silences still apply.

# Uptime Monitors

Create an HTTP monitor in the Admin **Uptime** page. Checks support `GET` and `HEAD`, one expected HTTP status code, intervals from 30 seconds to one day, configurable timeout, and consecutive failure/recovery thresholds. The page shows current status and recent check history; history is retained for 90 days. Uptime checks run from the server and allow only public HTTP/HTTPS endpoints. Private, loopback, link-local and local-name targets are rejected, credentials are not supported, and redirects are not followed.

Create an Alert rule using `uptime_unhealthy_count >= 1` to alert on active monitors in `warning` or `down`. Use `uptime_max_latency_ms` to alert on the largest most recent check duration in the project. Both metrics use the shared alert duration, silence, recovery, and Webhook delivery flow.

# Continuous Profiling

`MonitorClient.recordProfile` uploads a collapsed stack profile through the normal event queue. Stacks are listed from root to leaf and weights must be finite positive numbers:

```ts
client.recordProfile([
  { stack: ['onClick', 'renderPage', 'buildChart'], value: 12 },
  { stack: ['onClick', 'serialize'], value: 3 }
], { name: 'CPU', unit: 'samples' });
```

Browser SDKs can also collect JavaScript CPU profiles automatically where the experimental JS Self-Profiling API is available. Enable it explicitly with `profileSampleRate` (default `0`); `profileIntervalMs` sets the maximum capture window (1–60 seconds, default 60 seconds), and `profileSampleIntervalMs` requests a 10 ms sampling interval by default. The SDK shortens the window if needed to stay within the browser sample-buffer limit. The page must opt in with a `Document-Policy` response header. For compatibility with Chromium versions that still require the deprecated boolean policy, use `Document-Policy: js-profiling, js-profiling-mode=lazy`; newer implementations use `lazy`, while older versions may enable profiling eagerly. Unsupported browsers and pages without policy permission continue without profiling. Profile frames include function names and script paths with query strings and fragments removed.

Browser SDKs can collect estimated JavaScript memory samples through `performance.measureUserAgentSpecificMemory()`. Enable them separately with `profileMemorySampleRate` (default `0`); `profileMemoryIntervalMs` controls the randomized sampling interval (10 seconds–10 minutes, default 60 seconds). This experimental API is only called when the browser exposes it and the page is in a secure, cross-origin-isolated context (`crossOriginIsolated === true`); unsupported pages silently skip collection. Configure the page's COOP/COEP headers as required by its deployment to enable cross-origin isolation. Samples use the `JavaScript Memory` profile name and `bytes` unit and flow through the same ingest, ClickHouse retention, Admin query, and deletion paths as CPU profiles. Browser estimates are implementation-dependent and must not be compared across browsers or browser versions.

A profile window keeps up to 200 unique stacks, with at most 64 frames per stack and 96 printable characters per frame. Profile events are retained for 30 days, can be filtered by environment and Release in Admin, and are included in project/user data deletion. The browser sampler is experimental and only runs when explicitly enabled and allowed by the page's Document Policy.

## 业务上报与性能诊断

新增 `client.track/page/identify`、可选自动页面访问和可见停留采集，以及管理后台业务分析。接入方式、统计口径、采样与发送前钩子见 [业务上报说明](../docs/monitor-business-analytics.md)。性能数值统计按官方指标 ID 取最新报告，资源诊断提供网络阶段与未知数据说明。
