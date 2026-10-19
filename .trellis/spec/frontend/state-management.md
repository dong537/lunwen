# 前端 state-management

本项目规范以 [平台契约](../platform-contract.md) 为权威，开发前阅读全部接口、数据流、权限与验收约束。

- 后端：审核引擎保持在 backend；账号、提交、COS与队列属于 platform-backend。JPA负责查询，Flyway负责schema，不混写到controller。
- 前端：React TypeScript Vite，路由负责组合，API与Query集中处理服务状态。沿用CSS体系，不引入第二套UI框架。
- 数据：PostgreSQL两张业务表、UTC时间、字符串ID，未知统计为null。不得把失败或未审核显示为零问题通过。
- 权限：每个请求以数据库用户/导师关系为准，学生本人/名下教师可看论文，管理员不可看。首次改密由后端限制。
- 错误：中文稳定错误码和requestId，技术细节只入日志；不记录密码、cookie、COS密钥或论文正文。
- 验证：真实PostgreSQL集成测试、引擎Maven全量、前端test/build与浏览器流程；外部COS不能用stub结果冒充真实验收。
- 变更：限定模块边界，沿用统一envelope、分页和配置；测试结果与未验证事项写入任务check记录。
