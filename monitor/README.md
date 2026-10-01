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
