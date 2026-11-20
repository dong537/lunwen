# 生产部署

目标服务器：`docx-check.test.chatgqt.top`（`106.54.224.201`），部署目录 `/srv/docx-check`。

## CI

- `.github/workflows/ci.yml`：push 到 `main` 或手动触发，构建并推送三个公开镜像到 Docker Hub：
  - `15688463267/thesis-backend`
  - `15688463267/thesis-platform`
  - `15688463267/thesis-frontend`
- 镜像 tag：`sha-<完整 commit>` 和 `latest`。
- CI 不运行测试。

## CD

- `.github/workflows/deploy.yml`：只能手动触发，部署前会检查三个镜像在 Docker Hub 上存在。
- 触发命令：

```bash
gh workflow run deploy-production -R dong537/lunwen --ref main
# 或部署指定 tag：
gh workflow run deploy-production -R dong537/lunwen --ref main -f image_tag=sha-<commit>
```

- 工作流会把 `deploy/compose.prod.yaml` 和 `deploy/deploy.sh` 传到服务器，然后执行 `deploy.sh`。
- 发布流程：拉取镜像 → 启动 postgres → 启动 backend → 启动 platform → 强制重建 frontend（刷新前端对 platform 的 DNS 解析）→ 容器健康检查 → 本地和公网 HTTP 检查。
- 失败时自动回退到 `current-tag` 记录的上一版本；首次部署没有上一版本时保留现场。
- 当前没有数据库 schema 迁移步骤，Flyway 由 platform 启动时自动执行。

## 服务器一次性初始化

1. 安装 Docker、Compose、Caddy、curl。
2. 创建 `/srv/docx-check`，创建 `.env`（权限 600），内容参考仓库根目录 `.env.example`，其中：
   - `PLATFORM_HTTP_PORT=18090`
   - `SESSION_COOKIE_SECURE=true`
   - 当前生产使用 `TRANSFER_MODE=direct`、`COS_REGION=ap-shanghai`、`COS_ENDPOINT=cos-internal.ap-shanghai.myqcloud.com`、`COS_PUBLIC_ENDPOINT=cos.ap-shanghai.myqcloud.com`
   - COS CORS 允许 `https://docx-check.test.chatgqt.top` 的 `PUT/GET/HEAD`，并允许 `Content-Type` 请求头；新环境未配置 CORS 前先保持 `proxy`
3. 安装 `deploy/Caddyfile` 到 `/etc/caddy/sites.d/docx-check.caddy`（系统级 Caddy 统一监听 80/443 并自动签发证书），执行 `caddy validate --config /etc/caddy/Caddyfile && systemctl reload caddy`。
4. 确保 `ubuntu` 用户可免密 `sudo`，并且 `/srv/docx-check` 可写。

## 回退

```bash
# 服务器上直接回退到记录的上一个 tag
cd /srv/docx-check
./deploy.sh "$(cat previous-tag)"
```

### 仅回退论文审核引擎

账号、统计或其他平台功能需要保留时，使用独立工作流 `.github/workflows/rollback-engine.yml`，不要使用上面的整站回退命令：

```bash
gh workflow run rollback-thesis-engine -R dong537/lunwen --ref main \
  -f image_tag=sha-<40位完整提交号>
```

- 只接受从 `main` 触发，目标镜像必须已存在；沿用已登记的 SSH 主机指纹、部署密钥和生产并发锁。
- 仅拉取并替换 `backend`，不重建 `platform`、`frontend`、`postgres`，不改数据库及整站版本文件 `release.env`、`current-tag`。
- 检查引擎健康、平台到引擎的连接和其他容器是否保持不变；失败时尝试恢复操作前实际运行的引擎镜像。回退失败会明确报错，不能当作成功。
- `backend-current-tag` 记录最近一次单服务回滚成功的目标；后续整站部署可能再次更新引擎，因此实际运行版本以容器的镜像为准。
- 验收时查看 Actions 日志中的实际镜像与容器状态，并检查公网 `/api/health`。镜像回退不代表规则源码有差异，应另行比较对应提交中的 `backend`。
- 修改脚本后运行 `bash -n deploy/rollback-engine.sh` 和 `bash deploy/test-rollback-engine.sh`；后者使用隔离的命令替身验证成功及失败恢复路径，不连接生产服务。

### 仅发布前端

暂时隐藏页面等前端变更不能使用整站 deploy-production，否则会覆盖单独回退过的审核引擎。等 build-images 为目标提交构建好前端镜像后，使用：

    gh workflow run deploy-frontend-only -R dong537/lunwen --ref main -f image_tag=sha-<40位完整提交号>

该工作流复用生产 SSH Secrets、已登记的主机指纹、部署并发组和服务器 deploy.lock，只拉取与重建 frontend。它验证前端镜像 ID、健康检查、前端反向代理到平台的 /api/health，并断言 backend、platform、postgres 的容器 ID 不变。失败时优先从本机实际运行过的镜像恢复前端，不修改 release.env、current-tag、backend-current-tag 或数据库；成功后单独记录 frontend-current-tag。实际运行镜像以容器状态为准。发布后核对公网首页和 /api/health，以及审核引擎仍是已回退的镜像。

改动脚本时运行 bash -n deploy/deploy-frontend.sh 与 bash deploy/test-deploy-frontend.sh。隔离测试不连接生产 Docker 或服务器。
