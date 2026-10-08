# Strict OOXML 关系兼容审计

## 已覆盖

- 外部超链接同时支持 Transitional 和 Strict 关系 URI；其他外部关系仍拒绝，避免读取器访问外部模板或资源。
- 页脚关系同时支持 Transitional 和 Strict 关系 URI。
- `w:footerReference/@r:id` 同时读取两种 Office 文档关系命名空间。
- XML 静态解析入口同时接受 Strict 的 WordprocessingML 与 Math 命名空间，并映射到内部统一解析命名空间；原始部件字节和批注输出不被改写。
- 页脚、样式、章节分页和段落路径解析也统一经过该入口，避免局部解析器漏掉 Strict 命名空间。
- Strict DrawingML 主图形、图片和文字环绕命名空间也统一映射，覆盖图片及图片公式候选对象。
- 页码字段只解析被节引用的页脚部件，避免孤立页脚被误判。

## 验证

- `DocxReaderTest` 覆盖两种超链接 URI 和非超链接外部关系拒绝。
- `Docx4jDocumentParserTest` 覆盖 Strict 页脚 URI 与 Strict `r:id` 命名空间。
- 页脚回归同时替换主文档和页脚部件的 Strict WordprocessingML 命名空间。
- `TableParserTest` 覆盖 Strict WordprocessingML 命名空间下的表格边框解析。
- `EquationParserTest` 覆盖 Strict Math 命名空间下的 OMML 公式解析。
- `ImageParserTest` 覆盖 Strict DrawingML 图片对象识别。
- 批注写入根据主文档命名空间选择 Transitional/Strict `comments` 关系 URI，并在 Strict 文档中生成对应命名空间的批注范围、高亮和批注正文节点。
- Word/WPS 真实版面分页仍不能由 XML 解析直接证明，需要在权威排版环境中复核。
- 当前自动化回归验证了 Transitional 批注回读；Strict 批注还需用 Word/WPS 打开生成文件做最终兼容性确认。
