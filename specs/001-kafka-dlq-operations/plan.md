# 实施计划：Kafka 死信处理闭环

## 已确认上下文

- 相关模块：Spring Kafka 批量消费者、`DefaultErrorHandler`、Actuator/Prometheus、监控项目权限服务。
- 现有行为与约束：失败消息重试两次，间隔 1 秒；不可重试的参数错误直接进入 `<source-topic>.dlq`；项目权限可通过 `MonitorAdminService` 校验。
- 已确定：应用启动时为配置的来源主题创建或调整对应 `.dlq` 主题；默认保留 30 天，指标采集遇到主题不存在时显示 0 条。

## 方案

- 数据流／状态流：应用启动时创建或调整来源主题对应的 `.dlq` 主题并设置保留时间；定时读取死信主题起止偏移量及最早记录时间，发布 Micrometer 指标；Prometheus 告警规则观察指标；Owner 后台按主题查看摘要，再按 topic/partition/offset 读取单条死信并验证项目归属，重放时保留事件内容、消息 key 和 traceparent，发送到原主题并等待确认。
- 接口或组件边界：死信采集只发布指标；Controller 负责项目权限和请求参数；Service 负责从 Kafka 列举/精确读取、校验归属、Redis 防重复及回发；Admin 页面负责项目和来源主题选择、摘要展示、详情检查及重放反馈；消费者用 `BatchListenerFailedException` 表达单条失败索引。
- 兼容性与回退：不改变原有主题命名、重试次数、事件 DTO 和数据库；回退时移除新增指标组件、规则和重放接口，原有 Kafka 错误处理仍有效。

## 文件清单

| 文件 | 操作 | 原因 | 对应 AC |
| --- | --- | --- | --- |
| `src/main/java/com/macro/mall/tiny/modules/monitor/kafka/MonitorEventConsumer.java` | modify | 把可定位的单记录异常传给批量错误处理器 | AC-04 |
| `src/main/java/com/macro/mall/tiny/modules/monitor/service/MonitorDeadLetterMetrics.java` | add | 采集 DLQ 数量、最老记录年龄及采集状态 | AC-01 |
| `src/main/java/com/macro/mall/tiny/modules/monitor/config/MonitorDeadLetterTopicConfiguration.java` | add | 创建/调整死信主题并配置保留期限 | AC-05 |
| `src/main/java/com/macro/mall/tiny/modules/monitor/service/MonitorDeadLetterService.java` | add | 校验、读取和重放指定 DLQ 记录 | AC-02 |
| `src/main/java/com/macro/mall/tiny/modules/monitor/controller/MonitorDeadLetterController.java` | add | 提供 Owner 保护的查看和重放 API | AC-02 |
| `src/main/java/com/macro/mall/tiny/modules/monitor/dto/MonitorDeadLetterReplayRequest.java` | add | 定义单条重放请求 | AC-02 |
| `src/main/java/com/macro/mall/tiny/modules/monitor/dto/MonitorDeadLetterRecordView.java` | add | 定义死信查看/重放响应 | AC-02 |
| `src/main/java/com/macro/mall/tiny/modules/monitor/dto/MonitorDeadLetterSummaryView.java` | add | 定义死信列表摘要响应 | AC-03 |
| `admin/src/api/monitor.ts`、`admin/src/views/DeadLetters.vue`、Admin 菜单和路由 | add/modify | 接入摘要、详情和重放操作页 | AC-03 |
| `src/main/resources/application.yml` | modify | 配置死信指标采集周期 | AC-01 |
| `infra/prometheus/rules/monitor-kafka-dlq.yml` | add | 对积压数量和最老记录年龄告警 | AC-01 |
| `docs/monitor-kafka-dlq.md` | add | 说明指标、告警和手动重放方法 | AC-01、AC-02 |
| `ROADMAP.md` | modify | 记录已验证的功能进度 | AC-01、AC-02、AC-03 |

## 执行顺序

1. 改造批量消费者的失败定位，并精确释放失败记录及之后记录的去重占位。
2. 增加 DLQ 指标采集、Prometheus 告警规则和配置。
3. 增加 Owner 限定的单条查看/重放 API、Redis 重放防重和运维说明。
4. 增加后台摘要列表、详情和单条重放页面；增加死信主题保留配置和运维说明。
5. 编译服务端、构建 Admin、校验 Prometheus 规则和 Diff，记录 ROADMAP 与验证结果。

## 验证计划

| 层级 | 命令或操作 | 通过条件 |
| --- | --- | --- |
| compile | `mvn -q -DskipTests compile` | Java 服务端编译成功 |
| admin | `npm run build`（`admin` 目录） | Admin 页面构建成功 |
| prometheus | `promtool check rules infra/prometheus/rules/monitor-kafka-dlq.yml`（若本机可用） | 规则语法通过；若未安装，记录未运行 |
| diff | `git diff --check` | 无空白错误；已有用户改动不被覆盖 |

## 执行控制

- 状态恢复：off
- 独立审查：off
- 分支策略：当前分支 `develop-me`
- 提交策略：none

## 风险与回退

- 风险：单条重放仍可能再次失败；原死信记录会保留，后续新失败会生成新的死信记录。
- 风险：Kafka 管理或消费端临时不可用时指标刷新失败；采集状态指标应显示失败。
- 回退方式：移除新增 API/指标/规则，消费者恢复整批异常处理；无需数据库迁移。

## 审批

- Plan 状态：approved（后续按用户要求扩展管理后台页面和死信保留策略）
- 批准人：主人
- 批准时间：2026-10-16
- 审批证据：用户原话“你可，那按这个来做吧”，确认按前述方案实施；之后明确要求接入管理后台、补齐死信列表和主题保留策略。
