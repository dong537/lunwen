# 公式证据模型扩展实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为公式检查增加可审计的编号布局、OMML 编号定位、变量结构和 OLE/MathType 能力边界，减少误判与漏判。

**Architecture:** `EquationParser` 只提取 XML 证据，`EquationModel` 保存结构化证据，`RuleEngine` 只根据证据作规则判断。无法由 XML 证明的内部属性不伪造为格式错误，报告使用明确的能力状态。

**Tech Stack:** Java 17、DOM OOXML 解析、JUnit 5、Maven。

## Global Constraints

- 公式编号使用字符级精确匹配，不接受括号混搭、编号内空格或全角连接号。
- 不为单篇论文添加特例。
- 不把 MathType/OLE/图片公式内部不可读内容伪造成确定结论。
- 每项行为先写失败测试，再修改生产代码。

### Task 1: 公式编号与布局证据

**Files:** `EquationModel.java`, `EquationParser.java`, `RuleEngine.java`, `EquationParserTest.java`, `EquationStrictRuleTest.java`

- [ ] 为公式模型增加编号来源类型和布局类型，至少区分段落制表位、无边框双单元格表、OMML 编号数组、未知。
- [ ] 为双单元格公式表读取编号单元格段落的 `w:jc`、表格边框和单元格宽度证据。
- [ ] 为 OMML `eqArr` 记录编号所在行和编号运行路径，避免只靠公式段落文本重新搜索。
- [ ] 增加合法/非法布局回归测试并运行定向测试。

### Task 2: 变量上下标结构

**Files:** `EquationSymbolExtractor.java`, `EquationModel.java`, `EquationParserTest.java`, `RuleEngineTest.java`

- [ ] 从 OMML 的 `sSub`、`sSup`、`sSubSup`、`r` 节点提取主符号、下标、上标和完整变量 token。
- [ ] 过滤函数名、常量和单位，保留变量来源节点。
- [ ] 变量说明逐 token 匹配，不把 `x_1`、`x₁`、`X1` 混为普通字符串。
- [ ] 增加上下标、希腊字母、矩阵元素和缺失说明测试。

### Task 3: OLE/MathType 能力边界

**Files:** `EquationModel.java`, `EquationParser.java`, `EquationRuleChecker.java`, `RuleDescriptions.java`, tests

- [ ] 记录 OLE ProgID、预览关系和是否存在可解析 OMML。
- [ ] OLE/图片公式只检查外层编号、引用和可读取的布局证据。
- [ ] 内部字体、变量斜体、上下标无法读取时输出“内部结构无法从 DOCX 读取”，不生成错误批注。
- [ ] 增加 Equation.3、MathType、图片公式测试。

### Task 4: 验证与文档

- [ ] 运行公式解析、规则、报告和批注相关定向测试。
- [ ] 运行 `git diff --check` 和后端编译。
- [ ] 更新公式能力边界文档，列出 XML 可判定与不可判定项目。

