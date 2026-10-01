# Enterprise Frontend Observability Platform

基于 **mall-tiny 3.x** 二次开发的企业级前端可观测与异常监控平台，开发分支为 `develop-me`。

## 技术路线

- 基础框架：mall-tiny 3.x（Spring Boot 3.5 / Spring Security / JWT / RBAC / MyBatis-Plus / Redis）
- 工程化参考：RuoYi-Vue-Plus
- 监控与告警参考：Apache HertzBeat
- 异常诊断模型参考：Sentry
- 前端采集参考：web-see / web-see-demo / LianjiaTech fee
- 核心监控领域：独立实现

## 当前实现

### P0 - 基础设施

```text
MySQL 8.4
Redis 7.4
Kafka 4.3.1 (KRaft)
ClickHouse 26.8
MinIO
Docker Compose
```

### P1 - 数据接入

```text
POST /api/v1/envelope
        ↓
Bean Validation
        ↓
Redis project rate limit
        ↓
Kafka Producer
```

Topic：

- `monitor-error-v1`
- `monitor-performance-v1`
- `monitor-behavior-v1`
- `monitor-replay-v1`

### P2 - 数据消费

```text
Kafka
  ↓
Spring Kafka Consumer
  ↓
Normalize
  ↓
Error Fingerprint
  ↓
ClickHouse
```

当前已包含：

- Error / Performance / Behavior / Replay 分表
- MergeTree + 月分区
- Event TTL
- Error 小时级 Materialized View
- Error Fingerprint：动态 ID、hash 文件名和 query 参数归一化
- Project + X-Monitor-Key 鉴权（服务端仅保存 SHA-256 Hash）
- Redis EventId 幂等窗口
- Kafka Retry + Dead Letter Topic
- Error → Fingerprint → MySQL Issue 聚合
- Redis HyperLogLog 估算 Issue 影响用户数
- Fingerprint 单元测试

## 启动

```bash
docker compose up -d
mvn spring-boot:run
```

默认基础设施：

| Service | Address |
|---|---|
| MySQL | localhost:3306 |
| Redis | localhost:6379 |
| Kafka | localhost:9092 |
| ClickHouse | localhost:8123 |
| MinIO API | localhost:9002 |
| MinIO Console | localhost:9001 |

## 测试事件

```bash
curl -X POST http://localhost:8080/api/v1/envelope \
  -H 'Content-Type: application/json' \
  -d '{
    "eventId":"evt-001",
    "projectId":"demo-web",
    "eventType":"ERROR",
    "timestamp":1760000000000,
    "sessionId":"session-001",
    "release":"v1.0.0",
    "environment":"production",
    "pageUrl":"/order/detail",
    "sdkVersion":"0.1.0",
    "data":{
      "name":"TypeError",
      "message":"Cannot read properties of undefined",
      "file":"https://cdn.example.com/app.a1b2c3d4.js",
      "line":"1",
      "stack":"TypeError: Cannot read properties of undefined"
    }
  }'
```

## 下一阶段

1. TypeScript Monitoring SDK
2. SourceMap + Release
3. Breadcrumb / Replay + MinIO
4. Alert Engine
5. Vue3 Dashboard

## License / Attribution

本项目基于 mall-tiny（Apache-2.0）二次开发，并保留原 LICENSE。
其他参考项目仅用于架构、产品和领域设计研究；监控核心代码独立实现。