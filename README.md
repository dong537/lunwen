# 毕业论文格式审核平台

学生上传 DOCX 并下载带中文批注的论文，教师查看名下学生的提交，管理员维护账号及批量导入名单。审核引擎沿用既有学校规范和 XML 格式解析；网页显示审核状态和问题总数，具体修改意见查看 Word 批注。

## 服务与数据

| 目录/服务 | 职责 |
| --- | --- |
| `frontend/` | React、TypeScript、Vite；登录、强制改密、学生端、教师端、管理端 |
| `platform-backend/` | Java 21、Spring Boot、Security、JPA；权限、账号导入、提交、任务队列、COS |
| `backend/` | DOCX/XML 解析、章节规则、图表公式、Word 批注和内部检查报告 |
| PostgreSQL 16 | `sys_user`、`submission` 两张业务表；Flyway 管理 schema |
| 腾讯云 COS | 私有桶保存原始论文、标注论文和引擎结果 JSON |

浏览器 → 同源平台 API → PostgreSQL/COS → 内部审核引擎。只有前端端口对外开放。管理员无法查看论文内容或下载；学生仅本人，教师仅当前名下学生。初始和重置密码为 `123456`，后端强制首次改密，无自助注册。

上传仅支持非空 `.docx`，最大 50 MiB。提交状态为排队中、审核中、成功、失败；服务故障最多尝试 3 次，损坏文档直接失败。成功文件永久保留，失败记录保留但文件删除。教师的提交次数、最近提交时间、最新问题数都取成功审核记录。

规则不能保证所有论文百分之百准确；实际分页、字体替换和部分旧公式内部结构仍有证据边界。

当前开发版本在 2026-10-19 的代码审计中确认了 9 项尚未修复的问题，涉及跨页签会话、缓存权限、COS 日志及超时、调度、引擎错误分类和批注产物校验。详见 [代码审计报告](docs/implementation/2026-10-19-代码审计报告.md)。本次源码提交不代表这些问题已修复或生产环境已验收。

## Docker 启动

准备 Docker Compose、私有 COS 桶及具有相应权限的密钥。复制 `.env.example` 为本地 `.env`，填入数据库密码、初始管理员密码和 COS 配置；不要提交 `.env`。

```bash
docker compose config --quiet
docker compose up -d --build --wait
docker compose ps
```

默认入口 `http://localhost:8080`。生产 HTTPS 环境设置 `SESSION_COOKIE_SECURE=true`。镜像在容器内编译，不需要预先复制本机 JAR。首次初始化账号来自环境配置，数据库已有管理员时不会重置其密码。

本轮真实 COS 联调尚待用户提供桶与凭据配置；构建和单元/集成测试通过不代表云端文件流程已经验收。详见 [部署与开发说明](docs/部署与开发.md) 和任务验证记录。

## 开发与验证

需要 JDK 21+、Maven、Node.js 22.12+、Docker。平台测试使用真实 PostgreSQL Testcontainers；外部 COS/引擎边界可在测试中替代，不用于生产路径。

```bash
mvn -B -f backend/pom.xml verify
mvn -B -f platform-backend/pom.xml verify
cd frontend
npm ci
npm test
npm run build
```

引擎默认 18098，平台默认 18099，Vite 将 `/api` 转发至平台。平台本地环境变量与启动方式见部署文档。旧公式转换需 LibreOffice；引擎 Docker 镜像已包含它。

接口、状态和权限的权威契约见 [.trellis/spec/platform-contract.md](.trellis/spec/platform-contract.md)。任务计划、验证证据和未完成事项位于 `.trellis/tasks/10-18-thesis-platform/`。

论文原件、批注结果、解包 XML、日志和私钥不提交到仓库。
