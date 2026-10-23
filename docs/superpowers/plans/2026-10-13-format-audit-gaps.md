# 论文格式筛查缺口完善实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完善批注定位状态、图表结构关联、参考文献字段检查、公式类型分层和页码分节检查，并用真实论文回归验证。

**Architecture:** 保持 `Docx4jDocumentParser -> ParsedDocument -> RuleEngine -> CommentWriter/WordReportWriter` 主链路。新增能力优先扩展现有模型和解析器，不把视觉推断写进 XML 规则；所有无法安全定位的批注都保留为明确状态并写入报告。

**Tech Stack:** Java 21、Spring Boot、docx4j、JUnit 5、React/TypeScript。

## Global Constraints

- 不增加登录注册功能。
- 不把无法由 DOCX XML 确定的结论伪装成“正确”或“错误”。
- 批注正文使用中文，说明问题、原因和修改方法，不暴露 XML 专业术语。
- 不针对单篇论文增加特例；每个修复必须有规则级回归测试。
- 版面 PDF 分析本轮暂不实现。

### Task 1: 批注锚点状态与报告明细

**Files:**
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/reporting/CommentWriter.java`
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/reporting/AnnotationItem.java`
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/report/WordReportWriter.java`
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/api/InspectionResponse.java`
- Test: `backend/src/test/java/cn/luwneshacha/thesis/reporting/CommentWriterTest.java`

**Interfaces:** `AnnotationItem` 保留 `WRITTEN`，新增或统一 `UNSUPPORTED_ANCHOR`、`PATH_OUT_OF_RANGE`、`SOURCE_HASH_MISMATCH`、`EMPTY_ANCHOR`、`WRITE_FAILED`；报告表格增加“批注状态/未写入原因”列。

- [ ] 为每种未写入状态补充失败测试，断言状态、中文原因和报告中出现原因。
- [ ] 修改批注写入结果映射，确保页眉、页脚、文本框、复杂范围不会静默丢失。
- [ ] 修改 Word 报告输出，在每条失败结果后显示“已写入/未写入及原因”。
- [ ] 运行 `rtk mvn -s settings.xml '-Dtest=CommentWriterTest,CommentWriterReadbackTest' test`。

### Task 2: 图表与续表结构关联

**Files:**
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/document/ImageParser.java`
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/document/TableParser.java`
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/rules/TableRuleChecker.java`
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/rules/RuleEngine.java`
- Test: `backend/src/test/java/cn/luwneshacha/thesis/document/ImageParserTest.java`
- Test: `backend/src/test/java/cn/luwneshacha/thesis/document/TableParserTest.java`

**Interfaces:** 图题、表题和续表模型保留原始部件路径、前后关系、表头文本和结构证据；合并单元格和嵌套表格使用路径判断，不使用全局段落序号。

- [ ] 增加文本框/浮动对象/组合对象的解析测试，明确哪些位置可绑定、哪些位置返回不可支持状态。
- [ ] 增加拆分表格、重复表头、合并单元格和嵌套表格测试。
- [ ] 只有存在相同表号且结构证据足够时才检查续表表头；否则给出明确“无法确认”的报告项。
- [ ] 运行图表和表格定向测试。

### Task 3: 参考文献字段细分

**Files:**
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/document/ReferenceParser.java`
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/document/ReferenceEntryModel.java`
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/rules/ReferenceRuleChecker.java`
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/rules/RuleEngine.java`
- Test: `backend/src/test/java/cn/luwneshacha/thesis/document/ReferenceParserTest.java`
- Test: `backend/src/test/java/cn/luwneshacha/thesis/rules/ReferenceElectronicRuleTest.java`

**Interfaces:** `ReferenceEntryModel` 保留作者、题名、来源、年份、页码、URL、类型和载体；按 M/J/D/C/N/EB/OL 等类型输出字段缺失结果。

- [ ] 为书籍、期刊、学位论文、会议、报纸和电子文献分别写缺字段失败测试。
- [ ] 增加电子文献日期和地址检查，兼容常见中文标点但不放宽类型标识规则。
- [ ] 增加年份、页码、作者和题名的确定性检查，避免格式不完整文献被当作有效条目。
- [ ] 运行参考文献定向测试。

### Task 4: 公式类型分层检查

**Files:**
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/document/EquationParser.java`
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/document/EquationModel.java`
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/rules/RuleEngine.java`
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/report/RuleDescriptions.java`
- Test: `backend/src/test/java/cn/luwneshacha/thesis/document/EquationParserTest.java`
- Test: `backend/src/test/java/cn/luwneshacha/thesis/rules/RuleEngineTest.java`

**Interfaces:** OMML 检查内部结构；OLE/MathType/图片公式只检查外层编号、引用和可读取的字体/位置，并在报告中明确“内部结构无法从当前文件确认”。

- [ ] 为 OMML、OLE、MathType、图片公式分别增加编号、引用和变量说明测试。
- [ ] 统一公式状态文案，禁止把不可解析内部结构显示为“格式正确”。
- [ ] 保持现有公式编号连续性和章节编号规则。
- [ ] 运行公式定向测试。

### Task 5: 页码与分节结构检查

**Files:**
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/document/Docx4jDocumentParser.java`
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/document/SectionModel.java`
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/rules/RuleEngine.java`
- Test: `backend/src/test/java/cn/luwneshacha/thesis/document/Docx4jDocumentParserTest.java`
- Test: `backend/src/test/java/cn/luwneshacha/thesis/rules/RuleEngineTest.java`

**Interfaces:** 保存首页、奇偶页、链接前一节、页码起始值、页码格式和字段适用节；只对 XML 能确定的违规输出 FAIL。

- [ ] 增加首页不同、奇偶页不同、链接前一节和分节重置测试。
- [ ] 检查封面、摘要、目录页脚中的页码字段是否存在，并区分确定错误与无法确定。
- [ ] 对实际页码连续性保留明确限制，不根据 XML 段落数量推断页码。
- [ ] 运行页码定向测试。

### Task 6: 真实论文回归与交付验证

**Files:**
- Create: `backend/src/test/resources/regression/README.md`
- Modify: `docs/research/学校规则矩阵与核心验收清单.md`
- Test: `backend/src/test/java/cn/luwneshacha/thesis/inspection/InspectionKernelIntegrationTest.java`

- [ ] 选取现有 16 篇论文的脱敏副本或稳定测试路径，记录每篇论文的对象数量和关键规则结果。
- [ ] 增加回归断言：不出现已修复的空格、引用、页边距、续表和首行缩进误判。
- [ ] 运行 `rtk mvn -s settings.xml test` 和 `cd frontend; rtk npm run build`。
- [ ] 输出当前仍不支持的 XML/版面能力清单。
