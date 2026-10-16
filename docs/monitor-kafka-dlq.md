# Kafka 死信查看与重放

## 什么时候会进入死信主题

监控事件先进入对应的 Kafka 正常主题，例如 `monitor-error-v1`。消费者失败后，Spring Kafka 会按现有配置间隔 1 秒重试 2 次；仍失败时，把消息放进原主题名加 `.dlq` 的死信主题，例如 `monitor-error-v1.dlq`。参数或事件格式错误不做无意义重试，会直接进入死信主题。

应用启动时会为配置的监控来源主题创建对应的 `.dlq` 主题，并设置 Kafka 按消息时间清理，默认保留期为 30 天。Kafka 按日志段执行清理，因此超过 30 天的记录会在后续清理周期被删除，不代表到期那一刻立即删除。人工重放不会删除原死信；重放后如果仍处理失败，Kafka 会在死信主题里追加一条新的失败记录。

保留期、分区数和副本数可通过 `MONITOR_KAFKA_DLQ_RETENTION_MS`、`MONITOR_KAFKA_DLQ_PARTITIONS`、`MONITOR_KAFKA_DLQ_REPLICATION_FACTOR` 设置。默认分区数和副本数均为 1，适合当前单分区本地 Kafka；部署环境应让死信分区数不小于对应正常主题的分区数，并根据 Kafka 集群规模设置副本数。应用启动账号需要有创建主题和调整主题配置的权限。

## Prometheus 指标与告警

服务端每 30 秒刷新一次以下指标，管理端口仍使用项目现有的 Actuator Prometheus 抓取配置：

- `monitor_kafka_dlq_records{topic="monitor-error-v1.dlq"}`：按死信主题统计的积压数量，按 Kafka 起止偏移量之差估算。
- `monitor_kafka_dlq_oldest_message_age_seconds{topic="monitor-error-v1.dlq"}`：最老一条死信已等待的秒数；`-1` 表示无法读到其时间，`0` 表示当前没有积压。
- `monitor_kafka_dlq_metrics_up`：最近一轮 Kafka 指标采集是否成功，`1` 成功、`0` 失败。

`infra/prometheus/rules/monitor-kafka-dlq.yml` 有三条规则：死信连续 5 分钟大于 0 发出提醒；最老消息超过 15 分钟并持续 5 分钟发出严重告警；指标采集连续失败 2 分钟发出提醒。阈值是当前项目的起始值，可按实际告警量调整。

## 查看一条死信

在管理后台左侧打开“Kafka 死信”页面，选择项目和原始主题后，页面会自动查询该项目最近最多 100 条死信摘要；点击某条记录的“查看”后，才会读取并展示原始事件内容。列表扫描每个分区末尾最多 5000 个偏移量，因此用于定位近期消息，不是全量历史检索。旧记录可在“按位置查询”中输入死信分区和偏移量；不包含可验证项目编号或关键 Kafka 原始位置头的损坏记录不会出现在项目列表，也不能通过 Owner 接口查看或重放。

项目 Owner 可调用列表接口查询摘要：

```http
GET /monitor/admin/{projectKey}/kafka/dlq/{sourceTopic}/records?limit=100
```

`sourceTopic` 只能是服务端配置的监控来源主题，`limit` 范围为 1 到 100。服务端会校验项目 Owner 权限，并按事件中的项目编号过滤结果。

需要从 Kafka 运维工具直接核对分区和偏移量时，可以用下面命令读取。以本地 Docker Kafka 为例，命令只打印消息，不会删除死信：

```bash
docker exec monitor-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic monitor-error-v1.dlq \
  --from-beginning \
  --max-messages 50 \
  --property print.partition=true \
  --property print.offset=true \
  --property print.timestamp=true \
  --property print.key=true
```

使用项目 Owner 的登录态查看指定记录：

```http
GET /monitor/admin/{projectKey}/kafka/dlq/{sourceTopic}/partitions/{partition}/offsets/{offset}
```

例如 `sourceTopic` 为 `monitor-error-v1` 时，服务端会到 `monitor-error-v1.dlq` 查记录。接口会校验项目 Owner 权限和消息中的项目编号，只返回该项目的消息。返回对象包含原始事件内容与失败原因，事件内容可能包含业务数据，请勿把响应复制到公开日志或工单。

## 人工重放一条死信

确认失败原因已经修复后，在管理后台“Kafka 死信”页面点击“重放到正常主题”，或由项目 Owner 调用下面的接口，把这条记录发回原始主题：

```http
POST /monitor/admin/{projectKey}/kafka/dlq/replay
Content-Type: application/json
Authorization: Bearer <登录令牌>

{
  "sourceTopic": "monitor-error-v1",
  "partition": 0,
  "offset": 123
}
```

每次请求只处理一个分区偏移量。服务端会检查来源主题是否属于监控事件主题白名单、消息是否属于当前项目、事件类型是否和主题匹配，然后保留原消息编号、消息 key、traceparent 及原始分区，发回正常主题，并等待 Kafka 确认。相同死信偏移量 7 天内只能发起一次重放；原死信记录仍保留供审计和排查。重放成功后再次查看这条记录，页面会显示已重放状态和新写入的主题分区、偏移量。

不能安全识别所属项目，或缺少事件编号、发生时间、事件数据等必要字段的损坏消息不会通过这个接口重放。需要先按运维流程单独检查和修复；不要把死信主题整体自动转回原主题，否则坏消息会不断失败并形成循环。

## 批量消费失败的边界

JSON 无效、事件类型与主题不匹配、项目失效，以及单条 Issue/告警处理错误，都能关联到批次里的具体记录。此时先完成失败记录之前的消息，再从失败位置让 Spring Kafka 重试或转入死信。

ClickHouse 批量写入是一次整体数据库操作；如果数据库返回整批写入错误，系统无法证明是哪条事件导致失败，因此仍会按整批重试，耗尽次数后这批记录可能逐条进入死信。这种情况下应优先检查 ClickHouse 和批次数据，不能把整批失败误认为每条消息都格式错误。
