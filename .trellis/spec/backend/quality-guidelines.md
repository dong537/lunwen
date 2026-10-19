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

## 附录可选与适用范围契约

- 按用户 2026-10-19 最新要求，附录不是必写项，覆盖历史“每篇必须有附录”的口径。无实际附录时不产出缺附录、目录缺附录或附录格式问题；不得将没有结果称为附录检查通过。
- `DocumentRegionClassifier.isMainAppendixTitle(paragraph, document)` 与 `appendixHeadingLetter(paragraph, document)` 是实际附录证据入口。总标题和字母分标题均排除明确目录样式/自动目录域、表格和文本框；“见附录”等正文提及不等于附录。
- 手工目录缺样式或页码时，复用 `isPotentialContentsEntry` 与 `isContentsEntry` 的上下文判断，不能仅凭“附录”文字激活附录模块。弱排版证据限目录区域，Heading 样式/大纲/自动编号等明确标题证据优先；正文开始后真实附录即使误用了目录字体字号，也必须继续检查。扫描不能回调附录上下文方法，不在分类器字段中构造 `HeadingRuleChecker`，避免相互递归。
- 有实际附录时保留总标题格式、空行、分页证据、字母顺序和对象编号检查。总标题缺字母分项使用 `APPENDIX_SECTION_MISSING`；已有字母分项缺总标题使用 `APPENDIX_TITLE_MISSING`，不能改报整篇缺附录。
- 目录只有在论文实际含附录时才要求附录条目和数字页码；目录虚列附录仍检查标题对应关系。目录中缺页码的“附录”条目不能激活实际附录空行/分页规则。
- 修改适用性时同步 `RuleDescriptions`、`RuleCatalog` 与目录关系检查，避免报告继续要求无附录论文补写附录。`OptionalAppendixPolicyTest` 通过真实 DOCX 解析及 `RuleEngine` 验证这些边界；既有附录编号和格式回归不得取消。

## 图题格式与编号序列独立检查

- `ImageRuleChecker` 的 `FIG-05` 从已绑定图题读取明确的 `图章号-序号` 前缀，不以 `FIG-03` 的号题间空格、空格类型或图题正文格式合规为计数前提。例：`图5-1 正常`、`图5-2缺空格`、`图5-3 正常` 的序列均应通过，但第二项仍须报图题格式错误。
- 提取序号不得截断数字或带额外半角连字符的编号；`图 1-1`、全角连字符和前导零沿用原有格式/序列约束。保留分章计数、同一图题多个图形去重、正文与附录独立编号和真正跳号/重复的检查。
- `ImageRuleModuleTest` 和 `RuleEngineTest` 必须断言 `FIG-05` 实际执行的数量及判定，不能仅断言没有错误；真实 DOCX 路径由 `VisioCaptionAnnotationTest` 验证解析、字体字号、格式批注与编号计数相互独立。专测空格的夹具使用正确连续图号，避免混入首图从4开始等无关错误。

## 三线表实际占位与缺线诊断契约

1. **适用范围**：`TableParser.parse` 经 `TableModel.topBorder/middleBorder/bottomBorder` 向 `TableRuleModule` 提供三处横线证据；不得把 `tblGrid` 中未被实际单元格占据的列判为缺线。不引入列宽容差或样本名称特判。
2. **解析边界**：首尾横线遍历对应行的真实 `tc`（包括已有 `rowCells` 支持的包装节点），空文字单元格仍须检查。表头边界按 `gridBefore + gridSpan` 的半开区间交集检查；上下行没有实际交集或为空时不能通过。跨度计算仍受 XML 数值有效性和整数几何门控约束。
3. **优先级**：复用 `effectiveCellBorderState`，保留单元格、行例外、条件样式、表级的既有优先级。表头边界允许上下侧互补；显式 `nil/none` 不能被同侧表级边框覆盖，仅两侧均无更具体证据时使用表级线。
4. **结果契约**：`TAB-02` 的正常表和续表分别保留 `TABLE_BORDER_INVALID`、`TABLE_CONTINUATION_BORDER_INVALID`。`expected` 仅按“顶线、表头下横线、底线”顺序列出实际缺线，`actual` 为“缺少符合规范的”加该清单；不能把正常边线写成缺失。未知边框证据沿用单独诊断，不冒充通过。
5. **正反例**：行尾 `gridAfter=1` 对应省略位置、其他行更宽或合并格伸入该位置，本身不构成缺线；实际单元格部分缺线、显式取消边框仍必须失败。错例是对整张表全部网格槽强制补齐横线；正确做法是检查真实单元格和实际相邻的区间。
6. **报告与批注**：`RuleDescriptions.reason/suggestion` 和 `CommentWriter` 使用同一缺线清单，分别指向 Word“首行上边框、表头行下边框、末行下边框”。`TAB-02` 的空原因值保留通用建议，不得引入空指针错误。
7. **验证入口**：`TableParserTest` 覆盖左右省略、不同跨度及无交集、真实空格子、包装单元格、显式取消和非法数值；`TableRuleModuleTest` 覆盖正常表/续表七种缺线组合及全线存在；`RuleDescriptionsTest` 与 `CommentWriterReadbackTest` 验证报告和真实 Word 批注只描述失败边线。真实论文复测必须记录输入摘要、目标表和修复前后结果，验收稿从无旧批注原件生成。

## 图号引用与编号内部空白

- 正文引用识别独立于图题格式：`图5- 4`、`图5 -4`、连接符旁不换行空格/全角空格/制表符均可证明引用了 `5-4`。解析器先按顺序合并同段可见 run，匹配后复用分类器的编号等值比较。
- 仅连接符两侧允许水平空白；不得合并不同段落、跨换行符或拼接数字内部空白，不得把 `5-40` 当作 `5-4`。全角连接符是否等同半角继续使用既有政策，不能顺手放宽。
- `FIG-03` 图题空格、`FIG-05` 编号序列仍独立执行。引用有格式瑕疵不等于没有引用，禁止将明确引用报为缺失。
- 回归入口为 `ImageRuleModuleTest` 的正反编号边界及 `VisioCaptionAnnotationTest` 的跨 run DOCX 解析；真实论文需核对 `FIG-01` 从失败变通过，其他图题/布局/序列结果不受影响。
