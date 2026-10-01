# admin

Vue3 + TypeScript + Element Plus + ECharts 管理后台。

功能：

- Project / SDK Key
- Dashboard
- Issues / Issue Detail
- SourceMap
- Performance
- API Performance
- Release
- Session Replay
- Alert Rule / Alert Record

开发：

```bash
pnpm install
pnpm dev
```

构建：

```bash
pnpm build
```

生产 Docker 镜像由本目录 `Dockerfile` 构建，Nginx 将 `/api`、`/admin`、`/monitor` 反向代理到 Spring Boot。
