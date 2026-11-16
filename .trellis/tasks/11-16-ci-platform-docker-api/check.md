# 验证记录

- [x] 读取 GitHub Actions 原始失败日志：Testcontainers Docker API 1.32 被最低 1.40 的 daemon 拒绝，5 个测试为同一连接错误，业务断言失败 0。
- [x] 核对官方 1.21.4 发布说明及 Java 类默认 API 版本 1.44。
- [x] Maven 属性与依赖树检查：effective property 与 junit-jupiter、postgresql、jdbc、database-commons 均为 1.21.4。
- [x] Java 21 完整 Maven verify：177 项通过，0 失败、0 错误、0 跳过；保留了 Testcontainers/PostgreSQL 集成测试。
- [ ] 线上 main 工作流复跑并记录三个 job 结果。
- [ ] 确认无关的 backend-quality-full-summary.json 未纳入提交。
