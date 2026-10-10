# 本地开发与部署

## 环境

本机开发需要 Java 17、Maven 3.9+、Node.js 24 LTS、npm 10+、Docker Compose v2，以及 `curl`、`nc`。实验工具另需 Python 3.9+ 和 `lsof`。Windows 用户建议使用 WSL2。

`.nvmrc` 指定 Node.js 24；版本要求也在 `frontend/package.json` 中声明。

## 本机进程模式

```bash
cp .env.example .env.local
npm ci --prefix frontend
./scripts/dev-start.sh
```

脚本启动独立 Compose 项目的 MySQL、Redis 和 Mailpit，再启动 Spring Boot 与 Vite。默认前端地址为 `http://127.0.0.1:5173`，API 为 `http://127.0.0.1:8080`。

停止：`./scripts/dev-stop.sh`。脚本仅处理当前工作目录记录的进程和 Compose 项目；不要用全局停止命令清理其他项目。

## 全容器模式

仅需 Docker Compose v2；Node 和 Maven 在构建镜像中运行。

```bash
cp .env.example .env.local
./scripts/docker-start.sh
```

浏览器访问 `http://127.0.0.1:8088`。首次构建需要下载镜像和依赖；停止用 `./scripts/docker-stop.sh`，MySQL 数据卷会保留。

两个模式共用 `.env.local` 和同一个 Compose 项目，不同时运行。若需要多个副本，分别设置不同的 `COMPOSE_PROJECT_NAME` 及端口。

Docker Hub 无法访问时，可以在 `.env.local` 的 `MYSQL_IMAGE`、`REDIS_IMAGE`、`MAILPIT_IMAGE`、`NODE_IMAGE`、`NGINX_IMAGE`、`MAVEN_IMAGE`、`JRE_IMAGE` 中指定自己信任的镜像地址。公开模板仍使用上游镜像名；使用镜像服务后的验证应记录实际来源。

容器内 Maven 下载缓慢时，可在 `.env.local` 设置 `MAVEN_MIRROR_URL` 为自己信任的 Maven Central 镜像 HTTPS 地址；留空时使用 Maven Central。该参数仅用于镜像构建，不应包含凭据。BuildKit 会缓存 Maven 依赖以便重试。

## 演示账号与配置

首次运行 Flyway V1–V36 创建合成演示管理员 `admin` / `password`。普通用户可自行注册。不要把这些账号、示例数据库口令或开发 JWT 密钥用于公开服务。

`.env.local` 默认启用 `dev` profile、DEV 支付，并关闭支付宝沙箱、SMTP 外发及 Provider 调度。所有映射端口默认绑定环回地址；邮件只由 Mailpit 接收，不配置转发。

如需支付宝沙箱，先阅读 `scripts/alipay-sandbox-demo-start.sh` 和相关测试，使用自己的沙箱凭据，并在本地配置显式开关。生产资金不在本版本范围。

## 数据与故障排查

- 端口被占用：修改 `.env.local`；后端本机端口使用 `SERVER_PORT`，容器后端映射使用 `BACKEND_PORT`，前端代理使用 `VITE_API_PROXY_TARGET`。
- Docker 不可用：启动 Docker Desktop 或自己的 Docker runtime 后重试。
- 迁移失败：先查看当前数据库与 Flyway 记录。`reset-state.sh` 会删除本地数据卷，不用于保留数据的迁移修复。
- 上传内容、日志和运行身份保存在忽略目录，不带入公开提交。
- 构建必须使用 `npm ci` 和锁文件，不用本机旧依赖证明干净检出可用。
