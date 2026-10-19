# 后端 quality-guidelines

本项目规范以 [平台契约](../platform-contract.md) 为权威，开发前阅读全部接口、数据流、权限与验收约束。

- 后端：审核引擎保持在 backend；账号、提交、COS与队列属于 platform-backend。JPA负责查询，Flyway负责schema，不混写到controller。
- 前端：React TypeScript Vite，路由负责组合，API与Query集中处理服务状态。沿用CSS体系，不引入第二套UI框架。
- 数据：PostgreSQL两张业务表、UTC时间、字符串ID，未知统计为null。不得把失败或未审核显示为零问题通过。
- 权限：每个请求以数据库用户/导师关系为准，学生本人/名下教师可看论文，管理员不可看。首次改密由后端限制。
- 错误：中文稳定错误码和requestId，技术细节只入日志；不记录密码、cookie、COS密钥或论文正文。
- 验证：真实PostgreSQL集成测试、引擎Maven全量、前端test/build与浏览器流程；外部COS不能用stub结果冒充真实验收。
- 变更：限定模块边界，沿用统一envelope、分页和配置；测试结果与未验证事项写入任务check记录。

## 目录缩进与区域分类契约

- `ContentsRuleChecker.check` 对二、三级目录不产出 `TOC-ENTRY-INDENT`：不强制整体左缩进，也不把首行 2/4 字符变为必填项。不产出结果不等于检查通过。一级目录既有顶格检查保留。
- 二级仅首行 2 字符、三级仅首行 4 字符、仅整体左缩进、未设置缩进均不得由该项判错。解析器保留 `firstLine` 与 `left` 的独立含义，禁止相加、互相替代或为论文添加数值容差。
- 目录字体、字号、1.5 倍行距、编号空格、页码及标题对应关系独立检查；不能为跳过缩进而提前返回整个条目。
- 目录样式级别优先用于缩进适用范围；编号内空白不能把二、三级降成一级，未知级别不默认一级。样式级别不能覆盖编号空格或标题对应检查。
- `DocumentRegionClassifier.looksLikeLowerLevelContentsEntry` 仅是目录区域内的弱排版证据。目录与标题模块共用编号模式，防止缺/错页码的手工条目进入正文标题规则；不得把该谓词加入全局 `hasDefiniteContentsEvidence`，真实章节后的二、三级标题仍检查原有格式与首行缩进。
- 改规则时同步 `RuleDescriptions` 和 `RuleCatalog`；不能在批注建议或覆盖清单中保留“二级必须整体左 2、三级必须整体左 4”的旧口径。
- 回归通过 `RuleEngine`、真实 DOCX/XML 解析和 `CommentWriter` 回读验证：直接/继承首行属性、目录样式名、内部空白与缺页码组合、其他格式仍写批注、一级目录及正文缩进仍生效。主要入口为 `ContentsIndentPolicyTest` 和 `TocBodyClassificationRegressionTest`。
