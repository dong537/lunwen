# Word 自动编号章节识别修复计划

> **For agentic workers:** 本任务在当前会话内按步骤执行，使用 TDD；不启动子 Agent。

**Goal:** 读取 Word 自动编号章节的 OOXML 编号结果，避免一级标题漏识别并导致图、表、公式章节号误判；标题编号后的空格按 OOXML 可证明的字符精确核验。

**Architecture:** `Docx4jDocumentParser` 使用 docx4j 编号模拟器和单文档 `NumberingState` 读取展开后的编号标签，并从 `numbering.xml` 的有效级别定义解析 `lvlText` 尾随空格与 `suff`。这些证据与原始段落 run 分别存入 `ParagraphModel`。规则引擎计算编号 suffix、编号模板尾随 ASCII 空格和正文前导 ASCII 空格；tab、非 ASCII 空白或缺失定义不按空格通过。原始 run 与 XML 锚点保持不变。

**Tech Stack:** Java 21、docx4j 17.2.1、JUnit 5、WordprocessingML `document.xml` 与 `numbering.xml`。

## Global Constraints

- 章节编号以 DOCX 的 `numPr`、编号实例和 abstract numbering 定义为 Source of Truth。
- 不根据目录文字猜测正文当前章节；编号模拟失败时不合成编号。
- 自动编号标题的分隔符以 `numPr`、编号实例、级别覆盖和 abstract numbering 定义为依据；`w:suff` 默认值按 OOXML 规则处理，未知值不得按空格通过。
- 空格合规只统计 ASCII U+0020；制表符、不换行空格及其他空白字符不能被折算成普通空格。
- 图表题注空格、引用和编号格式仍严格按规范检查。
- 保留原文、现有批注和既有工作区改动。

---

### Task 1: 解析并使用自动章节编号

**Files:**
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/document/ParagraphModel.java`
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/document/Docx4jDocumentParser.java`
- Modify: `backend/src/main/java/cn/luwneshacha/thesis/rules/RuleEngine.java`
- Test: `backend/src/test/java/cn/luwneshacha/thesis/document/Docx4jDocumentParserTest.java`
- Test: `backend/src/test/java/cn/luwneshacha/thesis/rules/RuleEngineTest.java`

**Interfaces:**
- `ParagraphModel.numberingLabel()` 保留 DOCX 自动编号生成的标签；普通段落为 `null`。
- 章节标题文本由编号标签和可见段落文本组成，仅供标题/章节规则判断；批注 source 仍指向段落自身。
- 单文档编号计数由一个 `NumberingState` 顺序推进；编号定义缺失时标签为空。

- [ ] 写一个最小 DOCX 回归样例：第五章普通标题之后，`numbering.xml` 定义 `start=6`、`lvlText=第%1章`，第六章标题本身只含“软件测试”，其下表题为“表6-1 测试表”。
- [ ] 运行该测试，确认解析器未识别第六章且规则引擎错误报告表号章节序列。
- [ ] 使用 docx4j `Emulator` 和单文档 `NumberingState` 解析有效编号标签，并保留编号后缀空格/制表符语义。
- [ ] 在章节标题识别、分页章节清单和图表公式章节号计算中使用合成显示标题，不改变普通有序列表正文文本。
- [ ] 运行定向测试，确认自动编号标题被识别、表6-1不报章节序列错误，普通编号列表行为不变。
- [x] 覆盖自动编号标题的 suffix space、默认/tab suffix、正文前导空格、非 ASCII 空白和编号模板尾随空白。
- [ ] 运行 `rtk mvn verify`、`rtk npm run build`、`rtk git diff --check`。
- [ ] 用真实论文 API 复测，确认第六章题注按第六章计算，规范明确的空格/正文引用错误仍保留。

**Source of Truth:** DOCX `word/document.xml` 中段落的 `w:numPr`、`word/numbering.xml` 的 num/abstractNum/lvl 定义；目录不参与章节状态推导。

**Data Flow:** 上传 DOCX → `Docx4jDocumentParser` 解析正文及 numbering part → `ParagraphModel` 保留 `numberingLabel` → `RuleEngine` 识别章节并计算对象编号 → 现有报告与批注流程。

**Risks:** 列表编号可能重启或缺少对应编号定义；解析器不得把无法解析的编号默认为 1，也不得把普通项目编号误认为章节编号。回归需覆盖章节作用域及非章节列表不变。
