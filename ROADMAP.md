# 前端可观测监控平台 Roadmap

> 本文件是项目进度的唯一事实源。方案设计见根目录《企业级前端可观测监控平台_SpringBoot项目实施总纲.md》，阶段完成记录见 `docs/企业级前端可观测监控平台_完成状态与后续计划.md`。
>
> 当前进度快照：2026-10-02。完成状态文档记录的代码基线为 `830c740eec0505d0445c271d3ac04b203303856c`；当前 `develop-me` 分支 HEAD 为 `7a4aab2`。

## 当前阶段

**P0～P8 本地集成和浏览器主路径已通过；P6 Replay 播放、30 天对象保留及可选的错误前缓冲和 P7 Release/Alert 基础闭环已实现。项目成员权限已落地并迁移当前数据库。下一阶段聚焦 GitHub Actions 复验、部署发布链路、Replay 数据治理及日志/Trace 查询。**

- 进行中：修正了 CI 失败中发现的 Trace span 祖先断言；完整浏览器 E2E 三项及基础设施/告警 smoke 在本地均通过，需推送后复验 GitHub Actions；评估高于 80 req/s 的限流策略和长时资源表现。
- 已运行：部署 Compose 的 10 个长期运行服务和 Jaeger 卷初始化服务均属于 `monitor-platform` 项目并带统一 `com.monstereat.observability=true` 标签；数据库/消息/对象存储健康检查通过。基础设施 E2E 已将 Error、Performance、Behavior、Replay 事件写入 ClickHouse 并从 Admin API 查回；Prometheus 的应用与自身 target 均为 `up`。真实浏览器跨源请求已与 API → Kafka Producer → Consumer spans 在 Jaeger 形成同一 Trace。RustFS 是本地 S3 兼容验证替代服务，生产存储路线未改变。
- 尚未达成的关键验收：GitHub Actions 最新代码复验、Push/Pipeline 到构建/Release/部署/监控的流程，以及真实第三方 Webhook 接收端验收。本地内网 mock receiver 已成功接收 firing/resolved payload 并验证投递状态。80 req/s 测试通过；100 req/s 有 14.85% 请求触发项目每分钟 5000 条限流，不代表服务时延失败。
- 待环境：最新 GitHub Actions 正在运行；生产部署、真实第三方 Webhook 和 AI 分析还缺目标环境或凭据。Replay 对象和 DB 索引行均按 30 天保留，存储配额仍待实现。

## 已完成

以下阶段状态来自完成状态文档；其中 Java、SDK、Demo、Admin 的 CI 构建验证均报告通过。

| 阶段 | 状态 | 范围 |
|---|---|---|
| P0 基础环境 | 完成 | Spring Boot、MySQL、Redis、Kafka、ClickHouse、MinIO、Docker Compose 和 CI 基础 |
| P1 SDK + Ingest | 完成 | Error、Performance、API、Breadcrumb、白屏、Web Vitals、Event Protocol、持久化队列、批量上报、弱网重试、限流及 Kafka Producer |
| P2 Kafka + ClickHouse | 完成 | 批量消费、Consumer Group、Retry/DLQ、EventId 幂等、ClickHouse 批量写入、ReplacingMergeTree、TTL 和物化视图 |
| P3 Dashboard | 完成 | 项目切换、Dashboard、Issues、Performance、API、图表及时间/环境/Release/状态筛选 |
| P4 Fingerprint + Issue | 完成 | 错误归一化、SHA-256 指纹、Issue 聚合、影响用户统计、First/Last Seen、幂等和自动 reopen |
| P5 SourceMap + Breadcrumb | 完成 | Release、私有 SourceMap 上传及解析、错误调用栈逐帧还原及源码上下文、Breadcrumb 时间线、Issue 到 Replay 关联 |
| P6 Session Replay | 基础闭环完成 | rrweb 采集、Kafka、对象存储、Replay 元数据与后台播放器、普通及高风险路径采样配置、SPA 路由动态重选、对象 30 天生命周期、可选的错误前 60 秒/错误后 30 秒缓冲；错误缓冲默认关闭，容量配额与索引治理待办 |
| P7 Release | 基础闭环完成 | Release 元数据、Release Key、Git 信息及 SourceMap 上传脚本 |
| P7 Alert | 主功能完成 | Error/性能规则、Redis 滑动窗口、阈值、冷却、告警记录和 Webhook 基础能力 |

## 进行中与待办

### 实现与运行状态

- 本地与部署 Compose 固定为 `monitor-platform` 项目，所有长期服务和 Jaeger 卷初始化服务带统一 `com.monstereat.observability=true` 标签，并共享 `172.16.64.0/24` 默认网络。MySQL、Redis、Kafka、ClickHouse、RustFS 的健康检查通过；Jaeger、OTel Collector、Prometheus、Server、Admin 均运行中。RustFS 为本地 S3 兼容验证替代服务，未改变生产存储路线决策。
- Actuator 的健康探针、Micrometer Prometheus registry 和独立管理端口 `8081` 已配置；部署栈只把 `8081` 绑定到宿主机回环地址。Prometheus 已抓取应用、Prometheus 自身、HikariCP 和 Kafka Consumer 指标；测试 topic 当前 lag 为 0。两份配置通过 `promtool check config`。
- 已添加基础设施和真实浏览器 Trace E2E 脚本及 CI job，覆盖 Error、Performance、Behavior、Replay 的 Ingest、Kafka、ClickHouse/RustFS 写入及 Admin 查询，并加入唯一 LCP 规则 firing→resolved API 验收；Performance smoke event 还带随机 W3C `traceparent`，要求 ingest 响应保留 Trace ID，并轮询 Jaeger 验证 HTTP → `monitor-performance-v1 send` → `monitor.kafka.consume` 的祖先链。Playwright 浏览器测试点击 Demo API Trace Probe，核对 API 请求/响应 Trace ID、Behavior batch header/body 和 API → Ingest → Kafka Producer → Consumer 的祖先链。脚本会等待对象存储 readiness，成功或失败退出时都会禁用本次规则，告警记录和合成事件会保留供检查。CI E2E 使用独立的 `monitor-platform-ci-${GITHUB_RUN_ID}` project，Demo 与其他服务共享该 project、默认网络及统一标签，清理命令限定在该 project；固定宿主机端口仍意味着该 Job 不应在已有本地栈的同一 Docker host 并行运行。三次最新 GitHub Actions 的 `verify` 均成功，E2E 均在浏览器步骤失败；未登录日志 API 返回 403。当前 workflow 已将启动、三个独立 Playwright 用例、基础设施 smoke 和清理拆为单独步骤，失败时输出容器日志及 Playwright error context，以定位具体用例。本地三项 Playwright E2E 和完整基础设施/告警 smoke 均通过；新版 CI 复验待完成。
- 已添加 k6 Ingest 压测脚本；50 req/s × 1 分钟本地基线已通过，具体指标见 P8 和最近验证。
- Alert 已实现持续时长判定、恢复状态/通知、项目/规则/Issue 静默和带重试的 Webhook 投递记录；本地 API 运行探针已验证持续时长触发与恢复、规则/项目/Issue 静默期间抑制、解除后的恢复触发、静默到期自动恢复，以及失败 Webhook 的重试计数增长。Compose 内网 mock receiver 已成功接收 firing/resolved payload，4 条投递均为 `delivered`；真实第三方接收端尚未配置，投递记录暂存 Redis。E2E 合成 Alert 规则、记录、投递状态、测试事件及唯一测试 Issue 均已定向清理并核验无残留。
- Replay 已实现 Session 多片段合并、gzip 存储和 Issue 错误前 60 秒/后 30 秒窗口筛选；RustFS 写入、Admin API 解压读取和事件合并排序已通过。浏览器点击播放后自动滚到播放器，Demo 回放内容可见。SDK 的普通/高风险路由采样率默认保持兼容；`pushState`、`replaceState` 和 `popstate` 引发 pathname 变化时会停止并刷新当前片段，再按新路由重新抽样。部署配置在专用 Replay bucket 设置 30 天对象过期，并保留其他 lifecycle rules；运行态回读确认规则启用、bucket 当前未启用版本控制、现存 Replay 对象均不足一天。`retainOnError` 可选保留未采样会话的最近 60 秒并在错误后继续采集 30 秒，默认为关闭；本地浏览器 E2E 验证错误触发上传错误前片段，未启用时不上传。生产 profile 每小时按 30 天保留期分批清理过期 Replay 索引行，单轮限量并有单测；`create_time` 查询索引已加入新装 schema 和版本化迁移，并在当前库备份后应用、重跑确认幂等；存储配额仍待实现。
- SDK 与 Spring Boot 已实现 W3C TraceId 透传、服务端 Micrometer Tracing 和日志 MDC 关联；Browser SDK 支持 same-origin、all 和按 Origin 白名单传播，Demo 只允许监控 API 的 `http://localhost:8080`。SDK 会将 API 响应中的有效 `traceparent` 与 Behavior/Error 事件关联，并按上下文分组发送 Ingest 批次，避免将多个 Trace 混入同一请求；服务端通过 CORS 暴露响应 `traceparent`。真实浏览器从 `localhost:5174` 请求 API 后，其 Behavior 事件 → `/api/v1/envelope/batch` → Kafka Producer → Consumer 已在 Jaeger 组成同一 Trace，第三方请求仍不会自动传播 Trace。修正 OTLP HTTP endpoint 为完整 `/v1/traces` 后，强制采样请求已在 Jaeger 注册 `observability-platform` 服务。Spring Kafka Producer Observation 注入 W3C header；Spring Kafka 3.3 的 batch listener 由应用按批次创建 span，并将 Producer context 作为 parent/link。新增消费者单测构造含两个不同 Trace 的 Kafka batch，并已在本地 Kafka 运行态验证混合上下文 batch：单一 Consumer span 以第一个 Trace 为 parent，以第二个 Trace 建立 `FOLLOWS_FROM` link，两个事件均写入 ClickHouse。Issue 错误事件 API 和详情页也已加入 Trace ID 字段。Jaeger 已改为 Badger 本地持久化并设 7 天 TTL；向 Jaeger 写入 Trace 后重启服务，Trace 仍可查询。日志检索与 Trace/Issue 联查仍待实现。

### P8 生产级联调与交付（当前最高优先级）

- [x] 固定 Compose 项目名和统一服务标签；Compose 配置解析通过。
- [x] 为 MySQL、Redis、Kafka、ClickHouse 增加健康检查和启动依赖。
- [x] 启动 MySQL、Redis、Kafka 和 RustFS；MySQL 初始化出 16 张业务表，Redis `PING` 返回 `PONG`，Kafka topic 命令成功，RustFS readiness 和 MinIO 客户端基础认证/列桶成功。
- [x] 启动并连接本地 Compose 的 ClickHouse、Jaeger、OTel Collector、Prometheus、Server、Admin。
- [x] 验证基础服务端数据链路：Error / Performance / Behavior / Replay Ingest → Kafka → ClickHouse/RustFS → Admin 查询。
- [x] 通过真实浏览器验证 SDK 到 Admin 主流程：Vue Error → Issue → SourceMap → Breadcrumb → 错误前后 Replay。SourceMap 定位到 `../../src/App.vue:16:9`、Issue 页面显示源码和 2 条点击 Breadcrumb；1.46 MB SourceMap 上传成功。Issue `/issues/16` 跳转到同一 session 的 `errorAt` Replay，播放后自动滚到 iframe，Demo 内容可见。Alert 管理页另以临时性能规则验证 firing→resolved；该浏览器步骤不覆盖 Vue Error 自动告警，Webhook 成功投递结果见 P7 本地 E2E。
- [ ] 在 GitHub Actions 复验 CI E2E job（含 LCP firing→resolved）；上一轮 `verify` 成功而 `infrastructure-e2e` 因过严的 Trace 直接父节点断言失败。本地已改为检查 span 祖先链，三项浏览器 E2E 和完整基础设施/告警 smoke 均通过。
- [ ] 验收 Docker Compose 启动、业务服务连接和一次 Push/Pipeline 到构建、Release、部署、监控的流程。
- [x] 执行 k6 Ingest 基线：50 req/s、1 分钟、3001 请求；Consumer span 改动后 P95 3.79 ms、错误率 0%，对应 ClickHouse 有 3001 条新增事件，Kafka Consumer Lag 为 0（此前基线 P95 4.12 ms）。附加档位：80 req/s × 1 分钟 4800/4800 成功、P95 3.64 ms；100 req/s 共 6000 次，P95 3.86 ms，但 891 次被当前每项目每分钟 5000 条限流拒绝（14.85%），不是时延阈值失败。100 req/s 采样峰值 Server/Kafka/ClickHouse CPU 约 64%/117%/63%、内存约 751 MiB/973 MiB/1.54 GiB；80 req/s CPU 峰值约 73%/9%/79%，内存约 756 MiB/925 MiB/1.54 GiB。两档后 Kafka Lag 均为 0；对应测试 Release=`load-test` 的 ClickHouse 行数为 15,911（含此前基线）。本机结果不代表生产容量，更高压力和长时资源曲线仍待测。

### P7 Alert 生产级增强

- [x] 在本地运行态验收阈值恢复后的 RESOLVED 状态和恢复投递记录。
- [x] 在合成事件序列中验收 `durationSeconds` 持续时长行为。
- [x] 验收规则、项目和 Issue 作用域静默期间抑制及解除后的恢复触发；60 秒 TTL 到期后 API 不再返回 active silence，持续高值恢复触发告警。
- [x] 验收失败 Webhook 的重试计数与错误记录，以及 Compose 内网 mock receiver 的 firing/resolved 成功交付（4 条 delivery 均为 `delivered`）。
- [ ] 配置真实第三方 Webhook 接收端并验收外部网络交付；其他通知渠道待产品需求。

### P6 Replay 增强

- [x] 运行 RustFS S3 兼容服务联调并验收 gzip 对象写入/读取、多片段合并、排序和错误时间窗口筛选。
- [x] 验收播放器点击后自动滚到回放区域；真实浏览器从 Issue 详情传入错误时间并播放同一 session，页面自动滚动且 Demo 内容可见。服务端错误时间窗口筛选和浏览器展示均通过。
- [x] 增加普通 Replay 采样率、高风险路径前缀采样率和 SPA 路由动态重选；pathname 变化时结束/刷新当前片段并独立抽样，默认采样率仍为 1.0。Replay SDK 与 Demo 构建通过；query/hash 变化不触发重新抽样。
- [x] 实现可选的错误触发 Replay 保留及错误前 60 秒/错误后 30 秒上下文；SDK `retainOnError` 默认关闭，未采样会话在内存保留最多 60 秒/`maxEvents`，错误触发后绕过采样上传，本地浏览器 E2E 验证开启和关闭两种行为。生产启用仍需评估内存与隐私策略。
- [x] 设置专用 Replay bucket 的 30 天对象 lifecycle 规则；应用启动会保留 bucket 既有其他规则，运行态 `mc ilm rule ls` 已确认规则 Enabled 且为 30 天。对象生命周期异步过期不会清理 MySQL 索引行。
- [x] 增加 Replay 索引行治理：生产 profile 每小时按共享 30 天 retention 配置、每批 500 行且最多 10 批清理；`create_time` 索引包含在新装 schema 和 `V20261002_02` 幂等迁移。两项定向测试覆盖 cutoff 和单轮上限；当前库先备份再迁移，回读确认索引/版本各一条，重跑迁移保持幂等，未清理业务数据。
- [ ] 增加对象存储配额；错误缓冲能力已实现但默认关闭，生产启用策略仍待定。

### 后续平台能力

- [x] 全链路 Trace：强制采样服务端 HTTP → Kafka Producer → 批次 Consumer spans 在 Jaeger 中处于同一 Trace，Consumer 的 parent 指向 Producer；批次中的其他消息通过 Span links 关联。真实浏览器 Demo 的 API 请求响应 `traceparent` 被关联到 Behavior event，并沿批次 Ingest → Producer → Consumer 延续为同一 Trace。SDK/browser 构建及服务端镜像编译通过；真实 Kafka 混合上下文 batch 已验证一个 Consumer span 的 parent 与 link 分别关联两条独立 Trace。Jaeger Badger 本地存储设 7 天 TTL，并已验证重启持久性。
- [x] Issue 错误事件 API 返回 Trace ID 字段；Trace 检索和日志后端尚未接入。
- [x] Issue 详情解析 Chromium/V8 和 Firefox/Safari 常见 stack frame，按 Release 与环境逐帧 SourceMap 还原；未映射帧保留原始位置，已映射帧显示源码上下文。
- [x] 浏览器验收 Issue 详情页展示实际 Trace ID；Issue 16 显示 `1891daf3c81adb3427f78e2806f2bf20`，并与 Replay Session ID 区分。
- [x] Spring Boot Actuator/Prometheus 指标抓取：应用、Prometheus 自身 target 均为 `up`；HikariCP 与 Kafka Consumer metrics 已抓取，测试 topic Lag 为 0。
- [ ] 日志检索与 TraceId、Issue 关联。
- [x] 项目级成员权限：项目列表/查询按 membership 收敛；OWNER/MEMBER/VIEWER 分级控制写操作，密钥轮换和成员管理仅 OWNER；邀请对象必须已有 mall-tiny `/monitor/admin/**` RBAC 权限。版本化迁移已在当前库登记并回填既有 monitor admins。
- [x] 项目成员管理 UI 及权限自动化测试：可查看成员、邀请/更新 MEMBER 或 VIEWER、移除非 OWNER；6 项 Java 权限测试覆盖角色写入边界、非成员、停用管理员及禁止通过接口创建 OWNER。
- [ ] Tenant/Team 组织层级。
- [ ] 按真实数据量扩展 ClickHouse 物化聚合、冷热数据和聚合保留策略。
- [ ] 实现按项目、用户、日期的数据删除能力。
- [ ] 在真实 Error、SourceMap、Breadcrumb、Release、Trace 等证据基础上评估 AI 故障分析。

## 最近验证

- 2026-10-02：SourceMap stack 逐帧还原新增 V8/Gecko stack 解析和 fallback 单测；全量 Maven 测试 15 项通过，Admin `npm run build` 通过；测试上下文在无本地 Redis/MySQL 时记录后台定时任务连接失败日志。`git diff --check` 通过。
- 2026-10-02：本地 Compose 10 个服务均在 `monitor-platform` 项目、共享默认网络并带 `com.monstereat.observability=true` 标签；基础 Error/Performance/Behavior/Replay 链路、Prometheus 两个 target、HikariCP 与 Kafka Consumer 指标、Jaeger HTTP → Producer → Consumer Trace 均已验证。`bash monitor/scripts/e2e-smoke.sh` 通过。
- 2026-10-02：Java `prod` profile 与 CI 原样 `mvn -B test` 各 4 项通过；SDK/Demo 和 Admin 构建通过；两份 Compose、Prometheus/OTel/CI 配置、脚本语法与 `git diff --check` 通过。TraceIdFilter 返回的 `traceparent` sampled flag 与当前 span 的 sampled 状态相符，运行态 GET 均返回 200。Issue event API 和浏览器详情页均已显示 Trace ID；Issue 16 的 Trace ID 与 Session ID 已分别核对。GitHub Actions 尚未运行；E2E job 使用独立 Compose project，GitHub runner 与用户本地服务互不影响。
- 2026-10-02：真实浏览器 Vue 错误已显示 Issue、SourceMap 源码 `../../src/App.vue:16:9` 和 2 条 Breadcrumb；1.46 MB SourceMap 上传成功。Admin 告警页已观察临时规则 firing→resolved 并定向清理合成告警。约 4.46 MB Replay Kafka 消息曾超默认限制，调高 Producer/Broker/Consumer 限制至 10 MB 级后，同一 Session 的 13 个 rrweb 事件可写入和读取。Replay iframe 初始在视口下方；加入自动滚动并重建 Admin 容器后，浏览器从 Issue `/issues/16` 打开带 `errorAt` 的同一 session，点击 8-event 片段自动滚动，错误前后 Demo 内容可见。
- 2026-10-02：k6 50 req/s × 1 分钟 3001 请求，P95 3.79 ms、错误率 0%、Lag 0；80 req/s 共 4800/4800 成功、P95 3.64 ms；100 req/s 共 6000 请求、P95 3.86 ms，其中 891 次被每项目 5000 条/分钟限流拒绝。80/100 档采样的 Server/Kafka/ClickHouse CPU 峰值分别约 73%/9%/79% 和 64%/117%/63%，内存峰值约 756/925/1577 MiB 和 751/973/1577 MiB。
- 2026-10-02：Alert 本地内网 Webhook E2E 验证 firing 与 resolved JSON 均送达，4 条 delivery 状态为 `delivered`；本次唯一合成规则、告警、Redis 键和 ClickHouse 事件已定向清理并核验无残留。部署 Server/Admin 更新后仍有 10 个服务在统一 `monitor-platform` 项目、默认网络及标签下运行。
- 2026-10-02：Replay SDK 加入 SPA pathname 变化时按新路由重新抽样；Replay 包和 Demo 构建通过，History 包装在 controller.stop 时恢复。query/hash 变化不触发抽样。后续已实现默认关闭的错误前 60 秒/错误后 30 秒缓冲，Playwright 验证开启与关闭两种行为。
- 2026-10-02：Browser SDK 增加 Trace propagation Origin 白名单，Demo 仅白名单监控 API origin；只有请求方未提供 `traceparent` 时 SDK 才自动注入，XHR 原生设置失败不会留下错误标记。Browser 包、SDK workspace 和 Demo build 均通过。未白名单第三方请求不会由 SDK 自动注入 Trace Context；真实浏览器链路的后续验收记录见下条。
- 2026-10-02：本地使用唯一合成 Behavior Ingest + W3C `traceparent` 核验 Jaeger Query API：同一 Trace 下 HTTP Ingest → Kafka Producer → Consumer 共 7 spans；Producer 的 parent 为 HTTP span，Consumer 的 parent 为 Producer。唯一 ClickHouse Behavior event 已同步删除并核验计数为 0。
- 2026-10-02：CI E2E smoke 新增 LCP Alert firing→resolved 轮询验收；脚本语法与空白检查通过。退出清理会禁用本次唯一规则，合成告警和事件记录保留；脚本尚未在 GitHub Actions 运行。
- 2026-10-02：CI E2E Performance event 新增随机 W3C TraceId；脚本断言 ingest 响应传播 TraceId，并轮询 Jaeger 验证 HTTP → Kafka Producer → Consumer 的父子 span 链。Bash 语法、JavaScript load 脚本语法、Compose 配置和 Jaeger 查询过滤器 fixture 检查通过；没有运行会写入数据的 smoke 脚本。GitHub Actions job 尚未运行。
- 2026-10-02：CI 新增隔离 Compose project 内的真实浏览器 Trace E2E：Playwright 点击 API Trace Probe 并检查响应 `traceparent`、Behavior batch 的 Trace ID/header，以及 Jaeger API → Ingest → Kafka Producer → Consumer `CHILD_OF` 链。CI 同步构建 SDK/Demo，Demo 服务与栈共享项目、网络和统一标签，退出时只 down 本次 CI project。Playwright 依赖精确固定；SDK/Demo build、CI YAML、Compose 配置/挂载路径/标签、shell 语法、Playwright 测试发现（1 项）及 `git diff --check` 通过。测试未在本地写入型栈运行，GitHub Actions 尚未触发。
- 2026-10-02：新增 Kafka batch 混合 Trace 上下文单测：两条带合法且不同 W3C `traceparent` 的 Behavior ConsumerRecord 被交给同一 consumer batch，验证仅一个 parent 和另一 Trace 的 Link。隔离 Maven/JDK 17 容器执行 `mvn -B -Dtest=MonitorEventConsumerTraceContextTest test`，1 项通过；未连接或写入本地业务 Compose 栈。真实 Kafka 混合上下文 batch 的运行态验收仍待做。
- 2026-10-02：真实浏览器 Trace E2E：Demo 按钮从 `http://127.0.0.1:5174` 请求 `http://localhost:8080/admin/info`，服务返回应用层未登录响应；跨源响应头可读取 `traceparent`。Browser SDK 将响应上下文关联到唯一 Behavior event（eventId=`5f03e3c7-a056-4ef9-99c8-89109b016d6a`，traceId=`e68fe58b8510431c55fe69b4e1410844`），Ingest batch body 未泄漏 SDK 内部 traceparent。Jaeger 对同一 Trace 验证 `http get /admin/info` → `http post /api/v1/envelope/batch` → `monitor-behavior-v1 send` → `monitor.kafka.consume` 父子链成立。修改后 SDK workspace typecheck/build、Demo build 和 Spring Boot Docker 镜像编译通过；由于宿主机未安装 `mvn`，本轮未单独运行 Java tests。所有 10 个服务重建 server 后仍处于统一 Compose 项目和标签下。
- 2026-10-02：调研 Jaeger v2.21 官方 Badger 文档与配置示例：单节点 all-in-one 可用本地文件持久化且无需新增服务；官方 TTL 示例为 48 小时，实际保留期、容量和卷目录权限尚未确定，未修改配置。
- 2026-10-02：新增 Replay bucket 30 天生命周期规则；激活前确认 bucket 原先无 lifecycle rules，递归对象均在当日创建。Server 生产 profile 重建成功，`mc ilm rule ls` 回读 Enabled/30 days；MinIO Java SDK 会保留该 bucket 的其他规则。
- 2026-10-02：Jaeger v2.21 改为 Badger 持久卷与 168h spans TTL，BusyBox 初始化服务将卷设为 UID 10001；镜像内 `jaeger validate`、Compose 配置校验通过。生成带固定 TraceId 的服务端 Trace，重启 Jaeger 后仍能通过 Query API 查到；Jaeger Badger 为单节点本地存储。
- 2026-10-02：添加项目级成员访问控制及 `V20261002_01` 版本迁移。迁移前备份 `monitor_platform`，当前库应用后验证 3 个 OWNER membership、0 个活跃无主项目及迁移版本已登记；新装库 `sql/monitor.sql` 也包含 schema 和管理员回填。服务权限逻辑编译通过，邀请账号需已具备 mall-tiny 监控后台 RBAC。
- 2026-10-02：Java 17 `mvn -B test` 5 项通过；SDK/Demo workspace build 和 Admin build 通过。一次初始 Maven 测试因默认 `dev` profile 没有本地 MinIO 服务而触发新的生命周期初始化失败；将初始化限定到部署的 `prod` profile 后完整测试通过。
- 2026-10-02：修正 CI Trace E2E 对 span 直接父子关系的过严假设，改为验证 producer 在 Ingest span 后代链上；同时更新基础设施 smoke 的 Jaeger 断言以支持 Spring Security 插入的 `secured request` span。当前部署栈完整运行时三项 Playwright E2E（真实 API→Ingest→Kafka Trace、错误前 Replay 缓冲启用、默认关闭）全部通过；`monitor/scripts/e2e-smoke.sh` 完整通过，含数据持久化和 Alert firing→resolved。GitHub 上最新 `407075a` 的 `verify` 成功，E2E 在 Playwright 步骤失败；下一提交将逐项运行浏览器用例并输出失败上下文。
- 2026-10-02：用两条带不同 `traceparent` 的 Behavior 消息写入本地 Kafka 同一 topic；Jaeger 查询确认批次 Consumer span 以 trace A 为 parent、对 trace B 建立 `FOLLOWS_FROM` link，两条事件均落 ClickHouse。
- 2026-10-02：项目成员管理 UI 已加入成员查看、邀请/更新 MEMBER/VIEWER 和移除；新增 6 项项目访问权限单测。Admin 构建及定向 Maven 测试通过。
- 2026-10-02：Replay 元数据按 30 天保留期分批清理；定向 Maven 测试 2 项通过，未执行当前数据库清理。
- 2026-10-02：`V20261002_02` 在备份当前 `monitor_platform` 后添加 `monitor_replay.create_time` 索引并登记版本；迁移重复执行后仍仅有一条索引和一条版本记录。重建后的 prod Server 健康检查返回 `UP`；完整 `mvn -B test` 13 项通过，包含成员权限与索引清理测试。
- 仍待：GitHub Actions 最新提交复验、真实第三方 Webhook、生产部署流水线、对象存储配额、长时/更高容量目标。日志检索后端、Trace/Issue 联查、Tenant/Team 层级、条件数据删除和 AI provider 仍未完成。
