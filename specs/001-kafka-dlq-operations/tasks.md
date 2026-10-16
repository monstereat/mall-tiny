# 任务清单：Kafka 死信处理闭环

## T001：定位批量消费中的单条失败

- 依赖：无
- 允许文件：`src/main/java/com/macro/mall/tiny/modules/monitor/kafka/MonitorEventConsumer.java`
- 操作：为准备阶段和逐事件汇总阶段保留输入索引；报单记录异常时使用 Spring Kafka 批量失败异常；整批 ClickHouse 写入错误继续整批重试。
- 对应 AC：AC-04
- 验证：`mvn -q -DskipTests compile`
- 完成定义：失败索引对应原始 ConsumerRecord；偏移量之前的记录先完成处理；失败及之后的去重标记可释放重试。
- 状态：done

## T002：增加 DLQ 指标、告警和主题保留策略

- 依赖：无
- 允许文件：`src/main/java/com/macro/mall/tiny/modules/monitor/service/MonitorDeadLetterMetrics.java`、`src/main/resources/application.yml`、`infra/prometheus/rules/monitor-kafka-dlq.yml`
- 操作：启动时为监控来源主题创建/调整对应 `.dlq` 主题并应用可配置保留期限（默认 30 天）；周期采集死信主题的积压数、最早记录年龄和采集状态；增加 Prometheus 告警。
- 对应 AC：AC-01
- 验证：`mvn -q -DskipTests compile`；`promtool check rules infra/prometheus/rules/monitor-kafka-dlq.yml`（工具可用时）
- 完成定义：主题分区、副本数和保留时间来自配置；稳定主题标签的指标可抓取；无记录为 0；刷新异常不会覆盖成成功状态。
- 状态：done

## T003：增加受控查看和重放入口

- 依赖：无
- 允许文件：`src/main/java/com/macro/mall/tiny/modules/monitor/service/MonitorDeadLetterService.java`、`src/main/java/com/macro/mall/tiny/modules/monitor/controller/MonitorDeadLetterController.java`、`src/main/java/com/macro/mall/tiny/modules/monitor/dto/MonitorDeadLetterReplayRequest.java`、`src/main/java/com/macro/mall/tiny/modules/monitor/dto/MonitorDeadLetterRecordView.java`、`docs/monitor-kafka-dlq.md`
- 操作：按来源主题/分区/偏移量读取一条记录；校验项目 Owner、主题白名单和消息归属；使用 Redis 防重复后回发原主题。
- 对应 AC：AC-02
- 验证：`mvn -q -DskipTests compile`；`git diff --check`
- 完成定义：每次请求最多重放一条；发送确认后返回目标 topic/partition/offset；请求失败时释放防重复标记。
- 状态：done

## T004：接入 Admin 死信操作页

- 依赖：T003
- 允许文件：`admin/src/api/monitor.ts`、`admin/src/views/DeadLetters.vue`、`admin/src/layouts/MainLayout.vue`、`admin/src/router/index.ts`
- 操作：提供来源主题摘要列表、单条详情和重放操作，并显示重放结果。
- 对应 AC：AC-03
- 验证：`npm run build`（`admin` 目录）；`git diff --check`
- 完成定义：后台可从列表进入详情并发起单条重放，完成后能看到 Kafka 目标位置。
- 状态：done

## T005：更新项目进度和验证记录

- 依赖：T001、T002、T003、T004
- 允许文件：`ROADMAP.md`、`specs/001-kafka-dlq-operations/verify.md`
- 操作：记录实际实现、验证命令和未覆盖的运行态风险。
- 对应 AC：AC-01、AC-02、AC-03、AC-04、AC-05
- 验证：`git diff --check`
- 完成定义：只有通过编译和规则校验的内容标为完成。
- 状态：done
