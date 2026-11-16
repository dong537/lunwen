# 平台后端 CI Docker API 兼容修复

## 目标与范围

GitHub Actions 的 main/cde4ae6 项目验证 run 43850559426 中，只有 java (platform-backend) 失败；backend 与 frontend 成功。平台后端 5 个测试错误共用同一根因：Testcontainers 1.20.5 向 runner Docker 发起 API 1.32 请求，而 daemon 最低接受 1.40。保留真实 PostgreSQL/Testcontainers 集成测试，不以跳过测试或仅本机通过代替线上验收。

只在 platform-backend 的 Maven 依赖管理中覆盖 Testcontainers 为官方已修复近期 Docker Engine 兼容变化的 1.21.4。业务接口、数据库和前端不变；不修改 Docker daemon 或服务器配置。

## 验收

1. Maven effective property 与依赖树均解析为 Testcontainers 1.21.4。
2. Java 21 本地完整 Maven verify 通过，PostgreSQL 集成测试继续运行。
3. 推送修复后检查 GitHub Actions 同一工作流的 java (platform-backend)、java (backend)、frontend 均成功。

日志和外部证据：run 43850559426、job 133241673803；Testcontainers 官方 1.21.4 发布说明。
