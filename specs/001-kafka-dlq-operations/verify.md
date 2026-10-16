# 验证记录：Kafka 死信处理闭环

## 范围与 Diff

- 计划内文件：Spring Kafka Consumer、DLQ 指标/服务/Controller/DTO、`.dlq` 主题配置、Admin 操作页、`application.yml`、Prometheus 规则、运维说明、`ROADMAP.md`。
- 实际新增涉改文件：以上清单及本目录 Spec/Plan/Tasks/Verify 工件。
- 范围门禁：pass（人工对照本 Spec 与改动；没有修改数据库 schema、密钥、部署配置或运行中的服务）。
- 无关改动：工作区原本已有多处未提交修改；本轮保留它们，没有整理或覆盖。

## 自动验证

| 验证 | 命令／操作 | 结果 | 证据 |
| --- | --- | --- | --- |
| Java compile | `docker run --rm -v /Users/monster/Documents/GitHub/code/mall-tiny:/workspace -v /Users/monster/.m2/repository:/root/.m2/repository -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -o -DskipTests compile` | pass | Java 17/Maven 容器退出码 0；未运行测试 |
| Admin build | `npm run build`（`admin` 目录） | pass | 构建成功；保留既有大 chunk 提示 |
| Prometheus rules/config | `docker exec monitor-platform-prometheus-1 promtool check config /etc/prometheus/prometheus.yml` | pass | 主配置有效，发现并成功校验 3 个规则文件，其中新 DLQ 文件含 3 条规则 |
| Diff whitespace | `git diff --check` | pass | 无空白错误 |
| New-file whitespace | 对新加文档/Java 文件检索行尾空格 | pass | 没有命中 |
| Kafka runtime | 未执行 | not run | 未注入失败消息、未调用查看/重放 API、未重启服务 |

## 验收标准追踪

| AC | 结果 | 验证证据 | 备注 |
| --- | --- | --- | --- |
| AC-01 | pass | Java compile；Prometheus 主配置和全部规则通过 promtool | 真实积压指标抓取与告警触发尚未做运行态验收 |
| AC-02 | pass | Java compile；代码审阅确认 Owner 权限、主题白名单、项目编号匹配、偏移量精确读取和单条重放 | 尚未向实际 DLQ 写入数据并调用 API 验证 |
| AC-03 | pass | Admin build；代码审阅确认后台列表、详情和重放调用与服务端路由一致 | 未做浏览器端和 Kafka 运行态验收 |
| AC-04 | pass | Java compile；代码审阅确认先处理失败位置前缀，并将原始批次索引交给 `BatchListenerFailedException` | ClickHouse 整批写入故障仍按整批处理，这是无法归因到单条时的预期行为 |
| AC-05 | pass | Java compile；代码审阅确认启动配置包含分区、副本数、保留期限及默认 30 天 | 未对运行中的 Kafka 实例验证已有主题的配置更新 |

## 例外与风险

- 未运行 Maven 测试套件；本机没有 Maven，改用项目 Dockerfile 同版本 Java 17/Maven 容器执行 `-DskipTests compile`。
- Prometheus 规则仅做语法和配置校验；未重启/热加载运行中 Prometheus，因此当前运行进程不一定已加载新规则。
- 没有实际 Kafka DLQ 消息，所以计数、最老消息年龄及人工重放的运行态行为仍需后续验收。
- 死信主题的积压数量按起止偏移量之差估算；如果主题开启压缩或存在大量 offset 空洞，该值可能略高于实际记录数。
- 重放只允许配置中的监控事件主题，并要求项目 Owner；不能安全识别所属项目的坏消息会拒绝重放。

## 结论

- 状态：ready_for_review
- 审查人：Codex
- 审查时间：2026-10-16
- 人工验收证据：等待用户审阅；未声称 Kafka 运行态验收完成。
