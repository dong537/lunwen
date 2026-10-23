# 图片题注自动编号规则与锚点只读审查

日期：2026-10-20。源码权威为 `.worktrees/report-style-numbering-20261020`，HEAD 为 `bde33592b1364e885f4b7ce5758e97bda8ceb814`。本轮未修改业务源码、测试或规则，未读取或扫描任何论文，未运行 Maven；只读源码、既有测试，并运行一个使用现成 `target/classes` 的临时 Java 探针。

## 结论边界

**目前不能判断用户所说的“图3-1 系统结构框图”是否误报，更不能认定已经修复。** 主任务尚未取得对应实稿；实稿定位进展由 `artifacts/caption-numbering-investigation/README.md` 记录。本审查只给出能从源码确认、可用合成 XML 复现的条件。

发现两条需要与实稿对照的线索：

1. **编号分类未读取 `lvlText`。** 有效 `numPr` 指向 `numFmt=decimal` 时，即使 `lvlText` 为空、纯空格、固定文字、缺失或设置 `w:null=1`，当前解析器仍产出 `NUMBERED_LIST`，规则随即判为自动编号 FAIL。一个临时探针已复现这些分类结果。这证明存在“编号格式元数据”和“实际数字标签”之间的边界；并不证明目标实稿含有这些结构，也不代表已核验 Word/WPS 的最终显示。
2. **已有跨段批注可能影响地址解释。** 解析器路径计数包含所有 XML Element，批注 writer 的路径解析却跳过 `commentRangeStart/commentRangeEnd`。当源文件的批注标记出现在目标路径经过的同级序列时，静态推导可指到后一题注或图片段，也可能越界。此项是源码契约不一致，尚未对 writer 做动态复现；普通段内批注不会改变该段自身的路径。

`numFmt=none`、纯 bullet 和显式 `numId=0` 均已被排除。普通 REF/PAGEREF 不被当作 SEQ。无编号元数据、无编号域的手写图题不会仅因文字含“图3-1”被该规则命中。

## 事实源与数据流

下文源码路径相对于权威 worktree 的 `backend/src/main/java/cn/luwneshacha/thesis/`。

```text
RawDocx 的 document/styles/numbering XML
  -> document/AutomaticNumberingParser
  -> ParsedDocument.automaticNumbering（SourceAddress + Kind）
  -> rules/AutomaticNumberingRuleModule
  -> NUMBERING-01（FAIL 或 REVIEW，保留 SourceAddress）
  -> 批注筛选与 reporting/CommentWriter
```

`document/Docx4jDocumentParser.java:108` 调用独立编号解析器。图题文本、图片绑定和 `ParagraphModel` 的标题/文献投影不是本规则的事实入口。`rules/AutomaticNumberingRuleModule.java:11` 对已有证据直接分类，不按“这是图题”放行，也不重新判断可见数字。

当前项目政策确实包含由自动序号生成的图表编号：见 worktree 的 `docs/implementation/2026-10-19-全文禁止自动编号.md:3` 和 `.trellis/spec/backend/quality-guidelines.md:65`。因此发现真实 SEQ 图号本身符合当前禁用规则，不能仅因它位于图题就称为误报。该政策来自用户补充要求，不应冒称学校原文规定。

## `numPr`、格式与样式继承

关键实现：`document/AutomaticNumberingParser.java:189`—`215`。

```java
if (!hasNumPr) return null;
BigInteger id = integer(value(numId, "val"));
if (BigInteger.ZERO.equals(id)) return null;
// 缺失或非法 ID -> UNKNOWN_LIST；随后解析实际编号层级。
String format = value(direct(definition, "numFmt"), "val");
if (format == null) return UNKNOWN_LIST;
// 检查 NumberFormat 是合法枚举值。
return "bullet".equals(format) || "none".equals(format) ? null : NUMBERED_LIST;
```

这个函数及其 `listLevel` 调用没有读取 `lvlText`、标签的 `rPr/vanish`、文字颜色、字号或实际渲染结果。`XmlPartSupport.isBlankText` 虽能识别不可见格式空白，但编号分类没有调用它。

| XML 条件 | 当前结果 | 对图题调查的意义 |
| --- | --- | --- |
| 只有手写 `图3-1 ...`，没有有效 numPr 和编号域 | 不产编号证据 | 文本数字本身不是触发器 |
| `<w:numPr/>`，也无可继承 numId | UNKNOWN_LIST → REVIEW | 不会直接判为“已确认自动编号”FAIL |
| 只有 ilvl、缺 numId | UNKNOWN_LIST → REVIEW | 需区分报告中的待确认项与确定错误 |
| decimal + 空/空格/固定文字/缺失/null lvlText | NUMBERED_LIST → FAIL | 数字格式元数据足以触发，实际标签内容不参与判断 |
| numFmt=none 或 bullet | 不产列表证据 | 即使存在 numPr，也不会因该层级报 NUMBERED_LIST |
| 样式链继承 numId=1、有效 decimal | NUMBERED_LIST → FAIL | 当前段 XML 没有直接 numPr，不等于没有有效编号 |
| 空 numPr，但 Caption→Base 样式仍继承 numId=1 | NUMBERED_LIST → FAIL | 空 numPr 并不在此实现中表示显式取消 |
| 当前段显式 numId=0，样式继承非零 numId | 不产列表证据 | 显式取消优先，已按预期处理 |
| 标签 rPr 含 vanish、格式仍 decimal | NUMBERED_LIST → FAIL | 隐藏不等于取消；是否应按不可见编号豁免需先确认规则口径 |

样式优先顺序由 `paragraphProperties`（第 218 行）形成：当前段 pPr → 指定/默认段落样式 → basedOn 链 → docDefaults；numId 和 ilvl 分别取首次出现的元素。`listLevel`（第 244 行）解析 num→abstractNum、样式关联 lvl、lvlOverride、numStyleLink。当前段直接 ilvl 会关闭样式关联层级优先；只有被使用的 numPr 才会进入此流程，未使用的 numbering.xml 定义或仅有 lvl/pStyle 关联不会单独触发。

定义缺失、冲突、非法 numFmt 等返回 UNKNOWN_LIST。规则模块第 12—23 行明确将它映射为 `REVIEW / AUTOMATIC_NUMBERING_UNCONFIRMED`，而 NUMBERED_LIST、SEQUENCE_FIELD、LISTNUM_FIELD 映射为 `FAIL / AUTOMATIC_NUMBERING_FORBIDDEN`。不能将 UNKNOWN 的存在直接报告为确定自动编号错误。

## SEQ、REF 与不可见字段

`AutomaticNumberingParser.java:31` 只匹配指令开头的 `SEQ|LISTNUM|AUTONUM|AUTONUMLGL|AUTONUMOUT`，命令后必须是空白或字符串结束。第 155—172 行处理简单域与复杂域的 begin/instrText/separate/end；第 182—187 行添加证据。

- `SEQ Figure` 是生成序号，产出 SEQUENCE_FIELD；题注常见的 `图 + SEQ + 标题` 会命中当前政策。
- `REF bookmark`、`PAGEREF bookmark` 是引用，不产编号证据；即使书签名恰为 SEQ 或 LISTNUM 也不会匹配。
- 普通 `w:t` 中的“SEQ Figure”、域结果中的这些文字不会被当作指令。
- 嵌套域中的真实 SEQ 仍会命中；外层 REF/IF 的存在不自动豁免内部的序号生成器。
- 当前识别不解释 SEQ 的 `\h` 隐藏开关或运行的可见性。因此“看不到数字”不能单靠截图排除 SEQ 元数据，但也不能在未查指令时假设它存在。
- `excluded`（第 333 行）排除 del、moveFrom、delInstrText、属性修订旧值和 mc:Fallback；它没有普遍跳过 vanish 等隐藏格式。

## 批注锚点审查

锚点子审查为独立只读源码追踪，未读取论文或运行 writer 探针。

1. `AutomaticNumberingParser.java:141` 在当前 `w:p` 保存完整 XML `elementPath`，范围固定 `(0,0)`。`AutomaticNumberingRuleModule.java:23` 原样传递。它不使用“第几个非空段落”，因此普通空段落、表格、文本框不会因段落列表筛选而让地址自然加一或减一。
2. `reporting/CommentWriter.java:320` 按路径解析；第 315 行的邻段迁移只面向页眉、页码或 `-BLANK` 规则，NUMBERING-01 不命中。真正空编号段会走 EMPTY_ANCHOR；有 `w:drawing` 的图片段允许在原段写入（第 198—206 行）。图片自身所在段若继承 numPr，批注落在图片附近属于原段定位，不能直接称为跳到了图题。
3. SEQ/LISTNUM 同样只在段首写零宽批注，并非精准包围序号字符。复杂域记住 begin 所在段；跨段字段会锚定起始段。`CommentWriter.java:1095` 计算可见长度时不把 instrText 算作正文，因此通常不存在因指令长度累加而移到相邻段的问题，但字段级定位粒度确实粗。
4. **源文件已有跨段批注时有索引风险**：`document/XmlPartSupport.java:49`—`53` 将所有 Element 计入路径；`reporting/CommentWriter.java:1840`—`1846` 却跳过 commentRangeStart/commentRangeEnd。最小静态形状如下：

```xml
<w:body>
  <w:commentRangeStart w:id="0"/>
  <w:p><!-- 有效 decimal numPr：问题来源 --></w:p>
  <w:p><!-- 相邻图题或图片 --></w:p>
  <w:commentRangeEnd w:id="0"/>
</w:body>
```

解析器给编号段 `[0,1]`；writer 过滤标记后的 body 子序列为两个 p，同一路径 `[0,1]` 会指向第二个 p。若没有足够后继元素则可能 PATH_OUT_OF_RANGE。这是**静态推导的可复现条件，尚未动态验证 writer，不能断言用户原稿包含该结构**。标记必须出现在目标路径经过的层级；只有段内标记不会改变该段 p 自身的地址。

## 临时探针与证据

只运行了一个 `CaptionNumberingProbe.java`，构造内存 `RawDocx`/`RawPart` 和合成 document/styles/numbering XML，直接调用真实 `AutomaticNumberingParser.parse`；不是论文扫描，也没有用替身替换解析逻辑。程序内为每个结果写了断言，退出码 0、标准错误为空，19 个条件全部与预期当前行为一致。

- 临时源文件：`C:/Users/Lenovo/AppData/Local/Temp/caption-numbering-audit-20261020-3jy7ixbb/CaptionNumberingProbe.java`
- 探针 SHA256：`b8ecdc3c93c6dcf547f1ca3bc2a3b477ae06fd414553c9870bbd7137a9c337f7`
- 解析器源码 SHA256：`8a7084f50fcf7a4dc4e6183829c1beed3f1c51dfab57a559f5661973e5ec3bfa`
- 现成 `AutomaticNumberingParser.class` SHA256：`d9281a405dbe63f523ffc68498b8c66eedbfa4ebb4471bc3ae2aa074a7a14287`
- classpath：权威 worktree 的 `backend/target/classes` 与本机 Maven 缓存的 `docx4j-generated-objects-17.2.1.jar`；使用 Java 源文件模式 `java --class-path <classpath> <临时探针.java>`，没有重编译业务源码。
- 完整命令参数、19 行实际标准输出、退出码及探针摘要已记录到 `artifacts/caption-numbering-investigation/synthetic-probe-verification.json`；这是已完成的单次探针输出留档，没有追加第二次探针运行。输出记录 SHA256：`2459e09631cfbc98b07a0a4eb9b1c687a2b385eb7c53b552ca4f4e2f47e61cfc`。

当时执行的 Java 命令如下（外层 Python `subprocess.run` 传参列表执行，无 shell 插值）：

```powershell
java --class-path "C:/Users/Lenovo/Desktop/luwneshacha/.worktrees/report-style-numbering-20261020/backend/target/classes;C:/Users/Lenovo/.m2/repository/org/docx4j/docx4j-generated-objects/17.2.1/docx4j-generated-objects-17.2.1.jar" "C:/Users/Lenovo/AppData/Local/Temp/caption-numbering-audit-20261020-3jy7ixbb/CaptionNumberingProbe.java"
```

以下编号定义配合段落 `<w:numPr><w:numId w:val="1"/></w:numPr>` 即可重现核心边界；把 `lvlText` 的 val 改为固定文字或纯空格，分类仍相同：

```xml
<w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:abstractNum w:abstractNumId="1">
    <w:lvl w:ilvl="0">
      <w:numFmt w:val="decimal"/>
      <w:lvlText w:val=""/>
    </w:lvl>
  </w:abstractNum>
  <w:num w:numId="1"><w:abstractNumId w:val="1"/></w:num>
</w:numbering>
```

探针实测输出：

```text
manual_caption_no_numPr                 -> []
empty_numPr / ilvl_only_no_numId         -> UNKNOWN_LIST
decimal_empty_lvlText                   -> NUMBERED_LIST
decimal_fixed_lvlText                   -> NUMBERED_LIST
decimal_whitespace_lvlText              -> NUMBERED_LIST
decimal_missing_lvlText                 -> NUMBERED_LIST
decimal_null_lvlText                    -> NUMBERED_LIST
none_numFmt / bullet_numFmt             -> []
inherit_decimal_style                  -> NUMBERED_LIST
empty_numPr_with_inherited_id           -> NUMBERED_LIST
numId_zero_cancels_style                -> []
hidden_number_label                    -> NUMBERED_LIST
SEQ_caption / SEQ_hidden_switch         -> SEQUENCE_FIELD
REF_not_generator / PAGEREF_not_generator / literal_SEQ_text -> []
```

所有有证据的单段探针地址均为 `[0,0]:0-0`。它验证分类结果，不验证 Word/WPS 渲染，也不验证端到端批注写回；两种验证边界不能混淆。

## 既有覆盖与后续取证

`AutomaticNumberingParserTest.java:47` 覆盖正文、表格、文本框与路径解析；第 66 行覆盖 numId=0、none、bullet、REF/PAGEREF；第 77 行覆盖未知定义；第 86 行覆盖分裂/嵌套域；第 174 行覆盖样式关联层级。现有这些测试没有覆盖 decimal + 空/固定 lvlText、隐藏标签或源文件跨段批注标记的端到端路径。

`reporting/AutoNumberingCommentReadbackTest.java:72` 的自动序号用例虽然名称表达“只定位该数字”，实际断言覆盖批注数量、建议和正文保留，没有证明批注 XML 范围只圈中该数字。不能引用它证明字段精确锚定已验收。

取得实稿后需要定位该条 NUMBERING-01 的原始 kind/reasonCode 和 SourceAddress，再只核对相应段落的直接及继承 numPr、实际层级 numFmt/lvlText、字段指令，以及路径祖先序列中的已有批注标记。这样才能区分真实 SEQ/列表编号、不可见标签分类争议、UNKNOWN 待确认项、以及批注落点错误；在取得这些证据之前不应按图题内容豁免或修改规则。

工具边界：fast-context 在前序任务已因 `Windsurf API Key not found` 熔断，本次使用已知路径直读与 rg 精确搜索，没有重复请求失效工具。
