# ClickHouse 容量与保留治理

## 当前策略

默认部署使用 ClickHouse 26.8、`ReplacingMergeTree` / `SummingMergeTree`、按月分区和单一 `default` 本地存储盘。可选的 S3 冷层配置位于 `infra/clickhouse/config-s3-cold/monitor-s3-cold.xml`，通过 `docker-compose.clickhouse-s3.yml` 挂载；默认 Compose 不启用它。不要把本地 RustFS 的 Replay 对象存储当作 ClickHouse 冷层。

| 表 | 保留期 | 用途 |
|---|---:|---|
| `error_event` | 90 天 | 原始错误事件 |
| `performance_event` | 90 天 | 原始性能事件 |
| `metric_event` | 90 天 | 应用 Metrics 样本 |
| `behavior_event` | 30 天 | 行为事件 |
| `profile_event` | 30 天 | CPU / Memory Profile |
| `replay_event` | 14 天 | Replay 事件索引/事件 |
| `error_hourly` | 365 天 | 错误小时聚合 |
| `error_hourly_correction` | 400 天 | 聚合删除修正，额外留出修正窗口 |

规则定义在 `infra/clickhouse/init/01_monitor.sql` 和 `infra/clickhouse/migrations/`。ClickHouse 通过后台 merge 执行行级 TTL，过期时刻不等于物理文件立刻删除；当前 TTL merge 检查间隔为 4 小时。按月分区控制分区数量，数据类型与业务需求需要保留更久时，应先更新表 TTL、初始化 schema 和版本化迁移，再进行容量验收。

### 可选 S3 冷层

冷层使用 ClickHouse 的 S3 disk 和 `monitor_hot_cold` policy。默认部署不需要 S3 环境变量，也不会切换现有表。启用时，为所选 Compose base（`docker-compose.yml` 或 `docker-compose.deploy.yml`）叠加 `docker-compose.clickhouse-s3.yml`，并提供：

```text
CLICKHOUSE_S3_ENDPOINT=https://<s3-endpoint>/<bucket-or-prefix>/
CLICKHOUSE_S3_ACCESS_KEY_ID=<service-account-key>
CLICKHOUSE_S3_SECRET_ACCESS_KEY=<service-account-secret>
```

先创建 bucket 和专用服务账号；ClickHouse endpoint 指向 bucket/prefix，服务账号需要读、写和列举该 prefix。凭证只通过运行环境提供，不写入配置文件。先用 `docker compose -f <base-compose> -f docker-compose.clickhouse-s3.yml config` 检查展开结果，再在完成备份、验证 S3 连通性和确认冷数据查询 SLA 后，手动执行 `infra/clickhouse/optional/enable-s3-cold-tier.sql`。这份 opt-in SQL 独立于发布工作流自动执行的迁移目录：原始 Error/Performance/Metrics 保留 7 天热层、其余原有 TTL 保留删除期限；`error_hourly` 与 correction 表在 30 天后迁入冷层，并分别保留 365/400 天。迁移 TTL 由后台 merge 执行，不保证执行 SQL 后立即搬完历史 part。

S3 bucket 生命周期不能在 ClickHouse 仍引用对象时自动过期或删除对象；数据删除、恢复、服务账号轮换和 bucket 备份策略需要与目标 S3 服务一起验收。可以在本机执行 `infra/clickhouse/tests/s3-cold-tier.sh`，以隔离 MinIO 验证 TTL 搬迁到 S3、ClickHouse 重启后的回读，以及 opt-in 迁移对八张表的配置。该夹具不是云厂商兼容性或生产恢复验收。

## 可复算的容量预算

用真实数据按表估算，不把 Docker Desktop 的共享文件系统总量误认为 ClickHouse 专属容量。每日逻辑保留量按以下口径计算：

```text
table_retained_bytes = rows_per_day × bytes_on_disk_per_row × retention_days
planned_data_volume  = sum(table_retained_bytes) / 0.70
```

`/ 0.70` 为规划约定：让目标卷保留至少 30% 空间用于合并、摄入波动和元数据。每周从运行实例执行 [`capacity-report.sql`](../infra/clickhouse/diagnostics/capacity-report.sql)，结合业务逐表统计的日写入行数和当前 `system.parts` 压缩大小更新估算。多条信号可能来自一次前端请求，所以请求速率不能直接替代 ClickHouse 各表的行速率。

近期本地样本中 `performance_event` 为 50,222 行 / 5.08 MiB `bytes_on_disk`，约 106 个物理磁盘字节/行（包含列数据、marks 和 part 文件）；这是一次合成压测的本地观测值，只能用作演算示例，不能代表其他信号、Payload 分布或生产数据。假设每个请求恰好产生一条同样大小的性能事件，50 events/s 按 90 天保留约需 38.4 GiB；按 30% merge/headroom 预留，单表目标卷约需 54.8 GiB。上线前必须用目标流量和 payload 样本重新测量，并将错误、Metrics 等表分别相加。

## 告警与运行核对

`infra/prometheus/rules/clickhouse-capacity.yml` 按共享底层文件系统 80%/90% 使用率和 inode 余量告警。`clickhouse-retention-capacity.yml` 另外告警可用空间低于 10/5 GiB，以及 MergeTree 分区数据 part 总量超过 10,000。容器主机空间由多个 Docker 镜像、卷和服务共享，这些字节告警是保护宿主存储的信号，不表示 ClickHouse 表本身占满对应空间。

只读运行报表除 active parts/TTL/disk 外，也按表输出最近 30 天的观测行数、有效采样跨度、当前压缩字节/行和 TTL 全量保留量的规划估值。估值为 `压缩字节/行 × 观测行数/采样天数 × 保留天数 ÷ 0.70`；采样跨度不足 7 个日历日或表无 active rows 时不输出数字。该估算使用当前整表压缩率，假设近期日流量可代表持续负载，仅用于规划参考；它不会保存历史快照，也不能替代目标环境的增长曲线与代表性 payload 样本。

遇到容量告警时：

1. 执行只读容量报表，按表确认压缩数据量、行数、最旧/最新时间、part 数和已到 TTL 的 part。
2. 对照 7 天以上的逐表数据增长计算保留量；验证 TTL 是否符合数据政策、后台 merge 是否有积压。
3. 检查 Docker 主机共享空间的其他消费者。不要直接 `OPTIMIZE ... FINAL`、删分区或改 TTL 来清空间；这些操作可能引起额外 I/O、数据损失或历史查询缺口。
4. 如果测算超出本地热层容量，先决定原始数据保留窗口和聚合查询 SLA，再部署独立持久 S3/对象存储冷层并执行恢复/回读测试。当前项目没有完成此步骤。

## 外部验收仍需的输入

- 目标环境 ClickHouse 专属持久卷容量、磁盘类型与扩容边界。
- 各事件族真实峰值/日均行数、压缩字节/行和增长曲线。
- 原始数据、Replay 与聚合的业务留存要求、RPO/RTO 和历史查询 SLA。
- 如果要做冷热分层：生产 S3 服务、bucket/凭证管理、网络与存储成本，以及迁移后查询与恢复验收。

本机当前 ClickHouse `default` disk 的指标反映 Docker Desktop 共享盘。此环境只有一个本地 storage policy，不能据此声称生产容量目标或云端冷热层已完成。

实现依据：[MergeTree TTL 与磁盘迁移规则](https://github.com/ClickHouse/ClickHouse/blob/master/docs/en/engines/table-engines/mergetree-family/mergetree.md#ttl-for-columns-and-tables)；[ClickHouse S3 存储分层](https://github.com/ClickHouse/clickhouse-docs/blob/main/docs/guides/separation-storage-compute.md)；[ClickHouse Prometheus 指标接口](https://github.com/ClickHouse/clickhouse-docs/blob/main/docs/integrations/interfaces/prometheus.md)。
