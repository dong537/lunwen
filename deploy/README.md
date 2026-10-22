# 生产部署

目标服务器：`docx-check.test.chatgqt.top`（`101.35.223.219`），部署目录 `/opt/docx-check`。

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
gh workflow run deploy.yml -R dong537/lunwen --ref main
# 或部署指定 tag：
gh workflow run deploy.yml -R dong537/lunwen --ref main -f image_tag=sha-<commit>
```

- 工作流会把 `deploy/compose.prod.yaml` 和 `deploy/deploy.sh` 传到服务器，然后执行 `deploy.sh`。
- 发布流程：拉取镜像 → 启动 postgres → 启动 backend → 启动 platform → 强制重建 frontend（刷新 Nginx 对 platform 的 DNS 解析）→ 容器健康检查 → 本地和公网 HTTP 检查。
- 失败时自动回退到 `current-tag` 记录的上一版本；首次部署没有上一版本时保留现场。
- 当前没有数据库 schema 迁移步骤，Flyway 由 platform 启动时自动执行。

## 服务器一次性初始化

1. 安装 Docker、Compose、Nginx、Certbot、curl。
2. 创建 `/opt/docx-check`，创建 `.env`（权限 600），内容参考仓库根目录 `.env.example`，其中：
   - `PLATFORM_HTTP_PORT=18090`
   - `SESSION_COOKIE_SECURE=true`
   - `TRANSFER_MODE=proxy`（配置 COS CORS 并验证后再改 `direct`）
3. 安装 `deploy/nginx.conf` 到 `/etc/nginx/sites-available/docx-check`，启用站点。
4. 执行 `certbot --nginx -d docx-check.test.chatgqt.top --redirect`。
5. 确保 `ubuntu` 用户可免密 `sudo`，并且 `/opt/docx-check` 可写。

## 回退

```bash
# 服务器上直接回退到记录的上一个 tag
cd /opt/docx-check
./deploy.sh "$(cat previous-tag)"
```
