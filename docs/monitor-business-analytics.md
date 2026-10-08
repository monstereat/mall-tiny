# 业务数据上报与性能诊断

## 接入业务上报

```ts
import { init } from '@observe/browser';

const monitor = init({
  endpoint: 'https://monitor.example/api/v1/envelope',
  projectId: 'your-project',
  ingestKey: '<project-ingest-key>',
  release: 'web-1.0.0',
  environment: 'production',
  userId: 'business-user-id', // 可省略：匿名访问仍计 PV 和会话，不计已知用户 UV
  analyticsSampleRate: 1,
  capturePageViews: true,
  capturePageDwell: true,
  beforeSend(event) {
    if (event.pageUrl.includes('/private')) return null;
    return event;
  }
});

monitor.client.track('register_click', { source: 'header', button_id: 'register_entry' });
// 业务接口确认成功后再记录成功事件。
monitor.client.track('register_success', { method: 'password' });
monitor.client.identify('business-user-id');
// 退出登录：monitor.client.identify(undefined);
```

- `track(event, properties, options?)`：独立业务事件。名称使用小写 ASCII snake_case，最多 64 字符；属性最多 32 键、深度 3、16 KiB，字符串最多 1,024 字符，数字必须有限。非法输入返回 `null`，不打断业务。
- `page({ url?, name?, referrer?, properties? })`：手动记录页面访问，返回 `pageViewId`。已启用自动页面采集时，通常不需要重复调用；在切换用户后可显式记录新身份下的页面上下文。
- `identify(userId?)`：设置或清除用户标识，不上传用户画像。优先使用业务内部标识。
- `capturePageViews` 与 `capturePageDwell` 默认关闭；开启停留采集会同时开启页面访问采集。History/Hash 路由、初始页及 BFCache 恢复均有生命周期处理。
- 停留时长只累计页面可见区间，在隐藏、离开、路由切换、SDK 销毁时发送增量；后台标签页不计时。增量关联原页面 ID 与原身份，不能把识别用户之前的停留归给新身份。
- 页面 URL 去除账号、查询参数和非路由 fragment，保留 Hash 路由路径；仅改变查询参数不会产生新的自动 PV。业务属性中常见凭据字段先在 SDK 遮蔽，服务端继续执行既有脱敏。
- 同一 Window 的同项目/端点/凭据重复 `init` 返回已有实例；切换这些配置前必须先 `destroy()`，避免事件静默进入旧项目。其他配置变更也应销毁后重新初始化。
- `beforeSend` 在事件入队、持久化之前运行，支持改写或返回 `null` 丢弃，抛错也丢弃事件；恢复的旧离线事件也必须经过当前钩子，并回写过滤后的队列；不能改写事件 ID、项目或事件类型。被丢弃的错误不会触发 Replay 错误保留。

## 看板统计口径

管理后台新增“业务分析”：按项目权限读取，支持时间、环境、Release、页面和业务事件筛选，最多 7 天。

| 指标 | 口径 |
|---|---|
| PV | 按 `pageViewId` 去重的页面访问，不把停留增量算作访问 |
| 已知用户 UV | 页面访问中非空用户 ID 的精确去重；不冒充匿名 UV |
| 会话数 | 页面访问与业务事件中非空会话 ID 合并去重 |
| 业务事件数 | 仅业务 category，按 eventId 去重；不包含 API/Release Health 事件 |
| 可见停留 | 按事件去重、按页面 ID 合并增量，并关联查询窗口内页面访问 |
| 平均可见停留 | 关联停留总量 / 页面访问数；没有停留的访问也在分母中 |

业务采样独立于普通性能采样，默认 100%。设置低于 1 的 `analyticsSampleRate` 或 `track` 的 `options.sampleRate` 后，已收到的抽样业务事件和抽样页面访问会计入排除提示，不混入精确统计，也不按采样率估算真实数量。100% 采样仍不保证浏览器上报完全不丢；业务成功以服务端确认为准。

页面与事件排行各显示前 100，截断提示不影响全量总览。Demo 支持构建变量 `VITE_MONITOR_ENDPOINT` 指定接收端（默认开发端口 8080）；本地部署栈使用 8081。Demo 中点击“开启业务分析演示”，即可体验页面采集、注册入口点击和用户识别。底层复用现有 BEHAVIOR/Kafka/ClickHouse 链路，不新增数据库 schema。

## 性能与白屏改进

- Web Vitals 使用固定版本 `web-vitals@6.2.2`；CLS/INP/LCP 使用官方软导航支持，依赖浏览器实际支持能力，不将普通路由计时伪装成标准指标。[官方说明](https://github.com/GoogleChrome/web-vitals/blob/v6.2.2/README.md#report-metrics-for-soft-navigations)
- 上报带 `metricId` 和单调递增的 `metricUpdate`，数值统计先按项目、指标与 ID 取最新报告，再计算均值/分位数。无 ID 的历史事件仍按独立事件处理，不追溯改写旧数据；Explore 原始 count 保持事件计数语义。
- Resource Timing 补充 DNS、TCP、TLS、请求、响应阶段及解码后大小；Performance 页面提供资源诊断表。未知阶段显示“暂无数据”，不冒充零。
- 缓存 `hit` 需要可见证据；`miss` 在当前协议中表示发生网络传输，可能包含协商缓存；Service Worker 或字段不可见时归 `unknown`，没有明确证据不报 `bypass`。
- 白屏按配置根容器/骨架屏与视口采样启发式判断，连续确认后才上报；路由切换重检、销毁清理定时器。DOM 遍历设上限，超出时不推断白屏；这不是对所有遮罩、CSS 或渲染场景的绝对判断。

## 验证

SDK 的业务契约、Hash/History 生命周期、可见停留、官方指标回调、资源阶段、白屏及旧 HTTP Span 路径有定向测试。服务端覆盖项目鉴权、参数绑定、业务数据校验、采样、重复事件和统计。

```sh
cd monitor/sdk
pnpm build
node --test packages/core/test/*.test.mjs packages/browser/test/*.test.mjs
```

根目录执行 `python3 infra/clickhouse/tests/analytics-storage-acceptance.py`，会使用最新编译的 Java 查询服务和真实 ClickHouse，数据源替换为只读内联合成表；不读取业务数据、不落业务表、不运行登录或浏览器 E2E。先运行包含 `MonitorPerformanceSamplesTest` 的 Docker Maven 测试以生成 classpath。

本地部署栈的 Demo 构建示例：`VITE_MONITOR_ENDPOINT=http://localhost:8081/api/v1/envelope pnpm --filter monitor-demo build`（在 `monitor/sdk` 执行）。Demo 源码变化后，若需验证错误源码还原，应重新上传与该构建一致的 Release/SourceMap；这次业务分析构建未代替 SourceMap 发布流程。
