# 空行格式批注与完整性诊断契约

## 1. 适用范围
一级标题下真实空行的字体、字号检查，以及平台拒收部分未写入批注的审核结果。规则模块、源地址解析、批注写入和平台交付完整性各自保持职责。

## 2. 接口与事实源
`HeadingDocumentRuleModule` 产出的 `HEAD-02-BLANK-FONT`、`HEAD-02-BLANK-SIZE` 以实际空段落或空白 run 的 `SourceAddress` 为来源。`CommentWriter.writeCopy` 返回逐项 `AnnotationItem(status, reason, source, anchorSource)`。`HttpAuditEngine.inspect` 验证引擎响应及真实 Word 产物，不能把非 `WRITTEN` 项当成功。

## 3. 约束与数据流
空行字体规则只检查规范要求的中文黑体，字号独立精确检查三号（16 磅、32 半磅）。纯空段的段落标记、空 run、空白字符或手动换行不因未使用的 ASCII/hAnsi 等备用字体不是 Times New Roman 或未知而报 FAIL/REVIEW；字体槽存在不代表有对应文字。中文字体明确错误仍 FAIL、无法读取仍 REVIEW，不能照抄前一标题的实际错误属性作为标准。手动换行与标题共用 run 时，本规则仍只负责空行格式；标题、正文及引用中的真实字母数字继续由各自规则检查 Times New Roman。批注修复建议只说明实际不合规的中文字体，不要求修改空行里不存在的字母数字。

空行缺少可见文字并不否定其段落标记字体字号；确定不符合规范仍报 FAIL。两个精确规则 ID 接入既有安全零宽点锚，先检查原 source 合法范围，再按既有页首边界证据定位；无边界证据时使用原空行。run 来源的 start/end 是段落绝对偏移，须位于该可见 run 的真实绝对区间内，不能仅用整段总长度校验，更不能误当 run 相对偏移；来源为原段时才校验整段区间。迁移必须保留原 source 与实际 anchorSource，不能借任意邻段制造精确定位。不得插入可见占位符、修改字体字号、分页分节或补造论文内容。普通 BODY/FONT/SIZE 结果不获得通用空段豁免。

平台收到引擎非 WRITTEN 项时仍在下载产物前以原安全中文提示、不可重试语义失败。日志事件 `engine_annotation_incomplete` 只记录已校验 requestId、未写总数、最多 10 条样本及 omitted 数。样本仅含最多 64 字符且符合大写 ASCII 编码约束的 ruleId、允许的状态和稳定 reason；其余记 UNKNOWN。不得记录论文正文、expected/actual、文件名、签名 URL 或整个引擎 JSON。

## 4. 验证与错误矩阵
| 情况 | 行为 |
| --- | --- |
| 已确定字体/字号错误的真实空行，合法地址和范围 | 保留 FAIL，写入闭合零宽批注，返回 WRITTEN |
| Strict/Transitional 或仅有 pPr 的空行 | 使用对应 Word 命名空间，不新增可见文字 |
| 无关规则、非法路径或越界范围 | 保留明确拒绝，不伪造 WRITTEN |
| 原有系统批注稿再次审核 | 刷新系统批注，保留用户内容，不累积重复批注 |
| 任意批注未写入 | 平台仍失败；安全有界日志保留可追踪证据 |
| 日志字段含换行、超长、非字符串或未知原因 | 记 UNKNOWN，不泄漏原内容 |

## 5. 正常、基准与反例
正常：章节标题后空行的段落标记为错误字体或字号，规则保持两项 FAIL、同段合并一个零宽批注。基准：正确空行无相应 FAIL，既有文字批注行为不变。反例：只给规则加上 `-FONT` 就放行任意空段，或删除平台全写入门禁，都不能视为修复。

## 6. 必需验证
`HeadingBlankFontScopeTest` 通过公开 RuleEngine 和真实 DOCX 覆盖 pPr-only、空 run、普通/Unicode 空白、独立或与标题共用 run 的手动换行；未使用的英文字体缺失或非 Times New Roman 不影响空行中文黑体的通过，中文字体错误、字号错误和未知证据仍各自判定，真实标题/正文中的错误英文字体仍失败。

`HeadingBlankTypographyAnnotationTest` 穿过真实 DOCX、解析、公开 RuleEngine、批注写入与重检，覆盖段落标记、空 run 和空白文本 run。`CommentWriterTest` 覆盖两种 Word 命名空间、源与实际锚点、非法路径和范围、无关规则及属性保持。`HttpAuditEngineTest` 通过真实本地 HTTP 验证失败安全语义、无产物下载、有界诊断、注入/超长/类型反例与成功无误报。真实论文重测逐项对账全部待写/已写/未写，比较原稿摘要、正文、媒体及格式；HTTP200或产物可下载不足以证明完整。

## 7. 错误与正确做法
错误：新增空行格式规则只验证 RuleEngine 能报错，未验证 CommentWriter 能将该错误写入；失败后只留下泛化中文提示。正确：每项新规则同时验证真实源结构、写入和重检链路，失败日志仅保存必要的稳定定位编码。
