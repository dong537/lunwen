# 毕业论文格式审核平台

学生上传 DOCX 并下载带中文批注的论文与 Word 检查报告，教师查看名下学生的提交，管理员维护账号及批量导入名单。审核引擎沿用既有学校规范和 XML 格式解析；网页显示审核状态和问题总数，具体修改意见查看 Word 批注。

## 服务与数据

| 目录/服务 | 职责 |
| --- | --- |
| `frontend/` | React、TypeScript、Vite；登录、强制改密、学生端、教师端、管理端 |
| `platform-backend/` | Java 21、Spring Boot、Security、JPA；权限、账号导入、提交、任务队列、COS |
| `backend/` | DOCX/XML 解析、章节规则、图表公式、Word 批注和内部检查报告 |
| PostgreSQL 16 | `sys_user`、`submission` 两张业务表；Flyway 管理 schema |
| 腾讯云 COS | 私有桶保存原始论文、标注论文、Word 检查报告和引擎结果 JSON |

浏览器 → 同源平台 API → PostgreSQL/COS → 内部审核引擎。只有前端端口对外开放。管理员无法查看论文内容或下载；学生仅本人，教师仅当前名下学生。初始和重置密码为 `123456`，后端强制首次改密，无自助注册。

`TRANSFER_MODE=direct` 时，学生上传和用户下载使用平台签发的短期预签名 URL，浏览器直接与 COS 传输文件；平台只负责鉴权、预签名和 COS `HEAD` 校验，审核 worker 仍由平台服务器访问 COS。`TRANSFER_MODE=proxy` 保留旧的服务器中转链路。

上传仅支持非空 `.docx`，最大 50 MiB。提交状态为排队中、审核中、成功、失败；服务故障最多尝试 3 次，损坏文档直接失败。成功文件永久保留，失败记录保留但文件删除。教师的提交次数、最近提交时间、最新问题数都取成功审核记录。

规则不能保证所有论文百分之百准确；实际分页、字体替换和部分旧公式内部结构仍有证据边界。

## Word 检查报告

新提交审核成功后，学生本人和当前指导教师可以在提交详情下载批注稿与“检查报告”。报告为 `.report.docx` 文件，支持 COS 预签名下载和平台中转下载。报告生成、校验或保存失败时，不会显示为审核成功。

Word 检查报告采用简洁汇总：仅展示“检查模块、中文规范要求、问题数量”三列，同一规则的问题合并计数。待人工确认项单独统计；不显示具体问题、位置、内部规则编号或批注覆盖信息。问题较多时表格可以续页，具体修改意见仍在论文批注中查看。

升级时 Flyway 自动增加 `submission.report_available` 字段，历史记录默认为 `false`：已有原稿和批注稿不受影响，但旧记录不会凭空获得报告。需要报告时重新提交论文审核；不要仅修改数据库标志。若手动配置了 `PLATFORM_WORKER_LEASE`，必须大于审核引擎超时加 21 分钟；未配置时默认 40 分钟。

当前开发版本在 2026-10-19 的代码审计中确认了 9 项尚未修复的问题，涉及跨页签会话、缓存权限、COS 日志及超时、调度、引擎错误分类和批注产物校验。详见 [代码审计报告](docs/implementation/2026-10-19-代码审计报告.md)。本次源码提交不代表这些问题已修复或生产环境已验收。

## Docker 启动

准备 Docker Compose、私有 COS 桶及具有相应权限的密钥。复制 `.env.example` 为本地 `.env`，填入数据库密码、初始管理员密码和 COS 配置；不要提交 `.env`。

```bash
docker compose config --quiet
docker compose up -d --build --wait
docker compose ps
```

`.env.example` 按生产模板使用 `PLATFORM_HTTP_PORT=18090`、`SESSION_COOKIE_SECURE=true`；本地 HTTP 调试时改为 `8080` 和 `false`，入口为 `http://localhost:8080`。镜像在容器内编译，不需要预先复制本机 JAR。首次初始化账号来自环境配置，数据库已有管理员时不会重置其密码。

2026-10-19 已在腾讯云完成 Docker 部署，入口为 https://124.223.86.157 。真实 COS 上传、审核、Word 下载及四个账号的浏览器登录已验收；具体版本、验证边界和回退方式见 [账号平台生产部署记录](docs/implementation/2026-10-19-账号平台生产部署.md)。通用开发环境配置见 [部署与开发说明](docs/部署与开发.md)。

2026-10-22 新生产环境入口为 https://docx-check.test.chatgqt.top（`101.35.223.219`），当前使用 `TRANSFER_MODE=direct`：浏览器通过预签名 URL 直传/直下上海 COS，platform/worker 通过上海内网 endpoint 访问同一桶。镜像由 GitHub Actions 自动构建并推送到 Docker Hub，生产发布通过 `gh workflow run deploy-production` 手动触发；部署、回退和服务器初始化见 [deploy/README.md](deploy/README.md)。

## 开发与验证

需要 JDK 21+、Maven、Node.js 22.12+、Docker。平台测试使用真实 PostgreSQL Testcontainers；外部 COS/引擎边界可在测试中替代，不用于生产路径。

```bash
mvn -B -f backend/pom.xml verify
mvn -B -f platform-backend/pom.xml verify
cd frontend
npm ci
npm run typecheck
npm run build
```

前端 E2E 使用 Playwright（`npm test`），用例放在 `frontend/e2e/`；首次运行前需执行 `npx playwright install chromium`。

引擎默认 18098，平台默认 18099，Vite 将 `/api` 转发至平台。平台本地环境变量与启动方式见部署文档。旧公式转换需 LibreOffice；引擎 Docker 镜像已包含它。

接口、状态和权限的权威契约见 [.trellis/spec/platform-contract.md](.trellis/spec/platform-contract.md)。任务计划、验证证据和未完成事项位于 `.trellis/tasks/10-18-thesis-platform/`。

论文原件、批注结果、解包 XML、日志和私钥不提交到仓库。
