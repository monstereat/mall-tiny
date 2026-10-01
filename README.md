# Enterprise Frontend Observability Platform

基于 **mall-tiny 3.x / Spring Boot 3.5** 二次开发的企业级前端可观测与异常监控平台。

开发分支：`develop-me`

## 1. 项目目标

把前端线上问题从“用户反馈后人工排查”升级为完整可观测闭环：

```text
Browser / Vue / React
        ↓
monitor/sdk
Error / Performance / API / Breadcrumb / WhiteScreen / Replay
        ↓
Spring Boot Ingest
API Key / Validation / Rate Limit / Batch
        ↓
Kafka
        ↓
Consumer
Normalize / Idempotency / Fingerprint / Alert
        ↓
┌────────────┬───────────┬──────────┐
│ ClickHouse │   MySQL   │  MinIO   │
│ Raw Event  │ Issue等   │ Map/Replay│
└────────────┴───────────┴──────────┘
        ↓
Spring Boot Admin API
        ↓
admin / Vue3
Dashboard / Issue / Performance / API / Release / Replay / Alert
```

## 2. 参考方案与自研边界

- **mall-tiny 3.x**：Spring Boot / Spring Security / JWT / RBAC / MyBatis-Plus / Redis 基础骨架。
- **RuoYi-Vue-Plus**：企业工程化和后台设计参考。
- **Apache HertzBeat**：监控对象与告警体系参考。
- **Sentry**：Event → Fingerprint → Issue → SourceMap → Breadcrumb → Replay → Release 产品模型参考。
- **web-see / web-see-demo / LianjiaTech fee**：浏览器采集、行为轨迹、录屏与监控链路参考。
- **核心监控代码独立实现**：Ingest、Kafka、ClickHouse、Fingerprint、Issue、SourceMap v3/VLQ、Replay、Alert、Admin API、SDK。

## 3. 目录

```text
.
├── src/                         Spring Boot 服务端
│   └── .../modules/monitor/
├── monitor/
│   ├── sdk/
│   │   └── packages/
│   │       ├── core/
│   │       ├── browser/
│   │       ├── vue/
│   │       ├── react/
│   │       └── replay/
│   ├── demo/                    Vue SDK 联调 Demo
│   └── scripts/                 Release / SourceMap CI 脚本
├── admin/                       Vue3 管理后台
├── infra/clickhouse/init/       ClickHouse 初始化
├── sql/                         MySQL 初始化
├── docker-compose.yml           本地基础设施
└── docker-compose.deploy.yml    完整部署
```

## P0～P5 完成状态

| 阶段 | 状态 | 已实现内容 |
|---|---|---|
| P0 基础环境 | ✅ | mall-tiny 3.x、MySQL/Redis/Kafka/ClickHouse/MinIO、Docker Compose、CI |
| P1 SDK + Ingest | ✅ | Error/Performance/API/Breadcrumb、XHR+Fetch、白屏、Web Vitals、统一 Event Protocol、持久化 Batch Queue、指数退避、弱网恢复、Spring Boot 单条/批量 Ingest、Redis 限流、Kafka Producer |
| P2 Kafka + ClickHouse | ✅ | Kafka Batch Listener、Consumer Group、Retry/DLQ、EventId 幂等、ClickHouse Batch Insert、ReplacingMergeTree、TTL、Materialized View |
| P3 Dashboard | ✅ | Project 切换、Dashboard 指标/趋势、Issues、Performance、API 页面、ECharts、时间/环境/Release/状态多维筛选 |
| P4 Fingerprint + Issue | ✅ | Stack/URL/动态 ID Normalize、SHA-256 Fingerprint、Issue 聚合、Affected Users(HyperLogLog)、First/Last Seen、事件幂等、Resolved Issue 重现自动 reopen |
| P5 SourceMap + Breadcrumb | ✅ | Release、私有 SourceMap 上传、Source Map v3/Base64 VLQ Resolver、自动源码定位、源码上下文、Click/Route/Fetch/XHR Breadcrumb 时间线、Issue→Replay 跳转 |

> 当前 P0～P5 可代码化能力已经完成。数据库表结构、Mapper 和接口均已准备好；你后续只需要按实际环境补充/调整数据库连接、业务字段和真实数据，不需要重做核心架构。

## 4. 已实现闭环

### SDK

- JavaScript Runtime Error
- Promise `unhandledrejection`
- Resource Error
- Vue Error Handler
- React Error Boundary
- Fetch API 耗时和 5xx
- Breadcrumb：Click / Route / Fetch
- FCP / LCP / CLS / TTFB / INP
- Long Task
- White Screen
- rrweb Session Replay
- Batch Queue
- Sampling
- PageHide keepalive flush
- Online 网络恢复 flush
- 输入和敏感 DOM 默认遮罩

### 数据接入

- `POST /api/v1/envelope`
- `POST /api/v1/envelope/batch`
- Project + `X-Monitor-Key`
- Ingest Key SHA-256 存储
- Redis 分钟窗口 Rate Limit
- 单批最多 100 Event
- Kafka 异步削峰

Kafka Topics：

```text
monitor-error-v1
monitor-performance-v1
monitor-behavior-v1
monitor-replay-v1
```

### 消费与存储

- Spring Kafka Consumer
- EventId Redis 幂等
- Retry + DLQ
- Error Fingerprint
- MySQL Issue Upsert
- HyperLogLog 影响用户估算
- ClickHouse MergeTree
- TTL
- Materialized View
- Redis Alert Window
- MinIO SourceMap / Replay

### 故障定位

```text
Error
  ↓
Fingerprint
  ↓
Issue
  ↓
Release
  ↓
SourceMap v3 / Base64 VLQ
  ↓
原始 source / line / column
  ↓
Breadcrumb
  ↓
Session Replay
```

SourceMap 不公开到 CDN，由 CI 上传到 MinIO 私有桶。

### Release

- Release Key 与浏览器 Ingest Key 分离
- Release / Environment / Git Commit / Branch
- SourceMap Upload
- Java Source Map v3 / VLQ Resolver
- `monitor/scripts/release-and-upload-sourcemaps.sh`

### Alert

- Error Count Sliding Window
- Performance Metric Threshold
- Redis ZSet Window
- Cooldown
- MySQL Alert Record
- Webhook Notification
- Admin Rule Create / Update

### Admin

目录：`admin/`

页面：

- Login
- Projects
- Dashboard
- Issues
- Issue Detail
- Performance
- API Performance
- Releases
- Session Replay
- Alerts

管理端支持：

- 创建 Project
- 生成 Ingest Key / Release Key
- Key 轮换
- Issue resolved / unresolved / ignored
- SourceMap 原始源码定位
- Replay 播放
- 告警规则配置

## 5. 本地启动

### 5.1 基础设施

```bash
docker compose up -d
```

默认端口：

| Service | Address |
|---|---|
| MySQL | localhost:3306 |
| Redis | localhost:6379 |
| Kafka | localhost:9092 |
| ClickHouse HTTP | localhost:8123 |
| MinIO API | localhost:9002 |
| MinIO Console | localhost:9001 |

### 5.2 服务端

```bash
mvn test
mvn spring-boot:run
```

Swagger：

```text
http://localhost:8080/swagger-ui/index.html
```

### 5.3 SDK

```bash
cd monitor/sdk
corepack enable
pnpm install
pnpm build
```

### 5.4 Demo

```bash
cd monitor/demo
pnpm install
pnpm dev
```

### 5.5 Admin

```bash
cd admin
pnpm install
pnpm dev
```

后台登录使用 mall-tiny 原有管理员账号体系。

## 6. SDK 接入

Vue：

```ts
import { init } from '@observe/browser';
import { installVueErrorHandler } from '@observe/vue';
import { startReplay } from '@observe/replay';

const monitor = init({
  endpoint: 'https://monitor.example.com/api/v1/envelope',
  projectId: 'dcrm-web',
  ingestKey: 'mon_xxx',
  release: 'v2.3.1',
  environment: 'production',
  batchSize: 20,
  flushInterval: 5000
});

installVueErrorHandler(app, monitor.client);
startReplay(monitor.client);
```

React：

```tsx
import { ObserveErrorBoundary } from '@observe/react';

<ObserveErrorBoundary client={monitor.client}>
  <App />
</ObserveErrorBoundary>
```

## 7. 发布与 SourceMap

构建前在平台创建项目并保存 Release Key。

```bash
VERSION=v2.3.1 \
PROJECT_KEY=dcrm-web \
RELEASE_KEY=mon_xxx \
DIST_DIR=dist \
./monitor/scripts/release-and-upload-sourcemaps.sh
```

CI 流程：

```text
Build
 ↓
Create Release
 ↓
Upload *.map → MinIO
 ↓
Deploy JS（不上传 .map）
 ↓
SDK release=v2.3.1
 ↓
Error
 ↓
SourceMap Resolver
 ↓
src/*.vue / *.ts
```

## 8. 完整部署

```bash
cp .env.example .env
docker compose -f docker-compose.deploy.yml up -d --build
```

管理后台：

```text
http://localhost:8088
```

服务端：

```text
http://localhost:8080
```

## 9. 数据库职责

数据库字段和业务数据后续可以继续扩展，但职责边界固定：

- MySQL：用户、权限、Project、Release、Issue、Alert、Replay 索引。
- ClickHouse：Error / Performance / Behavior / Replay Event 和聚合分析。
- Redis：鉴权缓存、Rate Limit、幂等、影响用户估算、告警窗口、Cooldown。
- MinIO：SourceMap、rrweb Replay 大对象。

## 10. CI

`.github/workflows/monitor-ci.yml` 会验证：

1. Java Compile + Test
2. monitor/sdk Build
3. monitor/demo Build
4. admin Build

## 11. 后续可扩展

当前主链路已闭环，后续增强项：

- OpenTelemetry / TraceId 后端链路关联
- Spring Boot Actuator / Micrometer / Prometheus
- Log Search / OpenSearch
- 告警恢复通知和 Silence
- 更细的数据权限
- ClickHouse 更多物化聚合
- 压测与真实指标
- AI Issue Summary / Root Cause Suggestion

## 12. License

本项目基于 mall-tiny（Apache-2.0）二次开发，并保留原 LICENSE。

Sentry、web-see 等仅作为架构、功能和产品思路参考，不直接复制其受限源码。