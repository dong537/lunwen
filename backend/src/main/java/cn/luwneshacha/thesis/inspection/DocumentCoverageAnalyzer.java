package cn.luwneshacha.thesis.inspection;

import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RawDocx;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.RuleRun;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Reports document regions that current XML parsing or annotation does not cover. */
public final class DocumentCoverageAnalyzer {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String STRICT_W = "http://purl.oclc.org/ooxml/wordprocessingml/main";
  private static final String MC = "http://schemas.openxmlformats.org/markup-compatibility/2006";

  public Coverage analyze(RawDocx raw, ParsedDocument parsed, List<CheckResult> results) {
    return analyze(raw, parsed, results, List.of());
  }

  public Coverage analyze(RawDocx raw, ParsedDocument parsed, List<CheckResult> results,
      List<RuleRun> ruleRuns) {
    var unchecked = new ArrayList<UncheckedRegion>();
    var coveredParts = new java.util.HashSet<String>();
    for (var paragraph : parsed.paragraphs()) coveredParts.add(paragraph.source().partName());
    for (var table : parsed.tables()) coveredParts.add(table.source().partName());
    for (var equation : parsed.equations()) coveredParts.add(equation.source().partName());
    for (var image : parsed.images()) coveredParts.add(image.source().partName());
    for (var pageNumber : parsed.pageNumbers()) coveredParts.add(pageNumber.source().partName());
    for (var header : parsed.headers()) coveredParts.add(header.source().partName());
    var activeHeaderFooterParts = new java.util.HashSet<String>();
    for (var header : parsed.headers()) activeHeaderFooterParts.add(header.source().partName());
    for (var pageNumber : parsed.pageNumbers()) activeHeaderFooterParts.add(pageNumber.source().partName());
    for (var footer : parsed.footerPageEvidence())
      if (footer.partName() != null) activeHeaderFooterParts.add(footer.partName());

    for (var part : raw.parts().values()) {
      if (!part.xml()) continue;
      String name = part.name();
      String lower = name.toLowerCase(Locale.ROOT);
      // Package parts can remain after a section stops using them. Their text
      // and compatibility alternatives are not visible in the active thesis.
      if (isHeaderOrFooter(lower) && !activeHeaderFooterParts.contains(name)) continue;
      if (isHeaderOrFooter(lower)) {
        // Page-number parsing marks a footer part as covered, but it does not
        // cover unrelated prose in the same part. Evaluate the XML content
        // independently so a PAGE field cannot hide ordinary header/footer text.
        if (hasOrdinaryText(part.bytes())) {
          if (lower.contains("header")) {
            if (!coveredParts.contains(name))
              unchecked.add(new UncheckedRegion(name, "页眉文字格式", CoverageKind.NOT_INSPECTED,
                  "页眉包含文字，但解析结果中没有对应的有效检查证据",
                  "请确认该页眉是否被文档分节引用；封面不要求页眉，摘要至附录各节实际使用的页眉均会检查"));
          } else {
            addInspectionAndAnnotationGaps(unchecked, name, "页脚文字格式",
                "该文档部件存在普通文字，但当前规则只处理页脚页码，未对普通文字运行格式检查",
                "当前批注写入器不能把批注范围准确放入该文档部件",
                "如需自动检查该段文字，请先将内容移入正文区域；否则按学校格式要求核对页脚文字",
                "请根据报告中的问题说明修改；本次结果无法在页脚中直接定位批注");
          }
        }
      } else if ("word/document.xml".equals(lower) && !coveredParts.contains(name)
          && hasOrdinaryText(part.bytes())) {
        unchecked.add(new UncheckedRegion(name, "正文文字", CoverageKind.NOT_INSPECTED,
            "正文部件含有文字，但解析结果中没有可供格式规则检查的段落、表格或对象",
            "请在 Word 中另存为标准 .docx 后重新检查；如文字位于特殊对象中，请移入普通正文段落"));
      } else if (lower.equals("word/footnotes.xml") && hasNoteText(part.bytes(), "footnote")) {
        addInspectionAndAnnotationGaps(unchecked, name, "脚注内容",
            "脚注内容未纳入正文规则检查，当前无法确认字体、字号、行距等格式",
            "当前批注写入器不能把批注范围准确放入脚注部件",
            "请按学校要求单独核对脚注的字体、字号和行距",
            "如发现问题，请根据报告中对应区域的说明修改脚注内容");
      } else if (lower.equals("word/endnotes.xml") && hasNoteText(part.bytes(), "endnote")) {
        addInspectionAndAnnotationGaps(unchecked, name, "尾注内容",
            "尾注内容未纳入正文规则检查，当前无法确认字体、字号、行距等格式",
            "当前批注写入器不能把批注范围准确放入尾注部件",
            "请按学校要求单独核对尾注的字体、字号和行距",
            "如发现问题，请根据报告中对应区域的说明修改尾注内容");
      }
      if (isWordTextPart(lower) || isFormattingEvidencePart(lower)) {
        try {
          var selectedXml = cn.luwneshacha.thesis.document.XmlPartSupport.parse(part.bytes());
          Element root = selectedXml.getDocumentElement();
          if (isFormattingEvidencePart(lower)) {
            // docx4j can resolve a Choice differently from the DOM selector.
            // Without matched views, even a known Requires cannot prove the
            // effective style, numbering or page-variant setting.
            boolean relevantAlternative = hasActiveAlternateContent(root);
            if (relevantAlternative && "word/settings.xml".equals(lower)) {
              Element original = cn.luwneshacha.thesis.document.XmlPartSupport
                  .parseWithoutCompatibilitySelection(part.bytes()).getDocumentElement();
              relevantAlternative = hasActiveSettingAlternative(original);
            }
            if (relevantAlternative)
              unchecked.add(new UncheckedRegion(name, "兼容内容分支", CoverageKind.NOT_INSPECTED,
                  "样式、编号或页面设置有多种可选内容，无法确认 Word 或 WPS 实际采用哪一种；相关格式尚未可靠检查",
                  "请在常用 Word 或 WPS 中核对相关格式；如需自动检查，请另存为标准 DOCX 后重新提交"));
          } else {
            for (var gap : cn.luwneshacha.thesis.document.XmlPartSupport.compatibilityGaps(selectedXml)) {
              String region = gap.graphicOnly() ? "纯图形兼容内容分支"
                  : gap.textBoxOnly() ? "文本框兼容内容分支" : "兼容内容分支";
              String reason = gap.graphicOnly()
                  ? "同一位置有不同的浮动图形，无法确认最终图形和版面位置；该图形尚未可靠检查。"
                      + "分支外的段落文字仍继续检查。" + gap.reason()
                  : "同一位置存在不同的可选内容分支，无法确认 Word 或 WPS 最终显示哪一份内容；"
                      + "这部分格式尚未可靠检查。" + gap.reason();
              unchecked.add(new UncheckedRegion(name,
                  region + "（位置 " + gap.elementPath() + "）",
                  CoverageKind.NOT_INSPECTED,
                  reason,
                  "请在常用 Word 或 WPS 中核对该处内容与格式；如果需要自动检查，请另存为标准 DOCX 后重新提交"));
            }
          }
          if ("word/document.xml".equals(lower)) {
            reportUnparsedTables(unchecked, root, name, parsed.tables());
            if (!parsed.imageInventoryComplete() && hasActiveBodyGraphic(root))
              unchecked.add(new UncheckedRegion(name, "图片与图形识别范围",
                  CoverageKind.NOT_INSPECTED,
                  "文档中含有图片或图形，但本次无法确认是否已识别完整；部分图形的位置和相关格式尚未可靠检查",
                  "请在 Word 或 WPS 中核对图片、图形及对应图题；如需重新自动检查，请另存为标准 DOCX 后提交"));
          }
          if (isWordTextPart(lower) && hasElement(root, "txbxContent"))
            addInspectionAndAnnotationGaps(unchecked, name, "文本框文字",
                "文本框中的文字未纳入段落格式检查",
                "当前批注写入器不能保证批注准确锚定到文本框文字",
                "请将需要自动检查的文字移出文本框，或按正文格式要求单独核对",
                "本次不能在文本框文字上准确放置批注；请按报告说明修改文本框内容");
          if (isWordTextPart(lower) && hasRevision(root))
            unchecked.add(new UncheckedRegion(name, "修订内容", CoverageKind.UNSUPPORTED_FORMAT,
                "文档含有插入、删除或移动修订；当前检查未按 Word 修订显示状态解析其有效文本与格式",
                "请先在 Word 中接受或拒绝修订，再提交定稿进行检查"));
        } catch (Exception error) {
          unchecked.add(new UncheckedRegion(name, "XML 内容", CoverageKind.PARSE_ERROR,
              "该部件无法解析，具体格式规则未运行：" + error.getMessage(),
              "请在 Word 中另存为 .docx 后重新上传；若问题仍存在，请检查文件是否损坏"));
        }
      }
    }
    return new Coverage(new RuleCoverageAnalyzer().analyzeWithCatalog(results, ruleRuns),
        List.copyOf(unchecked));
  }

  private static boolean isHeaderOrFooter(String name) {
    return name.matches("word/(?:header|footer)[^/]*\\.xml");
  }

  private static void addInspectionAndAnnotationGaps(List<UncheckedRegion> target, String part,
      String region, String inspectionReason, String annotationReason,
      String inspectionSuggestion, String annotationSuggestion) {
    target.add(new UncheckedRegion(part, region, CoverageKind.NOT_INSPECTED,
        inspectionReason, inspectionSuggestion));
    target.add(new UncheckedRegion(part, region, CoverageKind.NOT_ANNOTATABLE,
        annotationReason, annotationSuggestion));
  }

  private static boolean isWordTextPart(String name) {
    return "word/document.xml".equals(name) || isHeaderOrFooter(name)
        || "word/footnotes.xml".equals(name) || "word/endnotes.xml".equals(name);
  }

  private static boolean isFormattingEvidencePart(String name) {
    return "word/styles.xml".equals(name) || "word/numbering.xml".equals(name)
        || "word/settings.xml".equals(name);
  }

  private static boolean hasActiveAlternateContent(Element element) {
    // Match XmlPartSupport's visible-content exclusions: former properties and
    // deleted text cannot change the formatting used by the current document.
    if (isNonVisibleRevision(element)) return false;
    if (MC.equals(element.getNamespaceURI()) && "AlternateContent".equals(element.getLocalName()))
      return true;
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
      if (child instanceof Element childElement && hasActiveAlternateContent(childElement)) return true;
    return false;
  }

  private static boolean hasActiveSettingAlternative(Element element) {
    if (isNonVisibleRevision(element)) return false;
    if (MC.equals(element.getNamespaceURI()) && "AlternateContent".equals(element.getLocalName())
        && containsRuleRelevantSetting(element)) return true;
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
      if (child instanceof Element childElement && hasActiveSettingAlternative(childElement)) return true;
    return false;
  }

  private static boolean containsRuleRelevantSetting(Element element) {
    if (isNonVisibleRevision(element)) return false;
    if (W.equals(element.getNamespaceURI())
        && ("evenAndOddHeaders".equals(element.getLocalName())
            || "themeFontLang".equals(element.getLocalName()))) return true;
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
      if (child instanceof Element childElement && containsRuleRelevantSetting(childElement)) return true;
    return false;
  }

  private static boolean isNonVisibleRevision(Element element) {
    return W.equals(element.getNamespaceURI()) && ("del".equals(element.getLocalName())
        || "moveFrom".equals(element.getLocalName())
        || "delInstrText".equals(element.getLocalName())
        || element.getLocalName().endsWith("PrChange"));
  }

  private static boolean hasOrdinaryText(byte[] xml) {
    try {
      Element root = cn.luwneshacha.thesis.document.XmlPartSupport.parse(xml).getDocumentElement();
      return containsOrdinaryText(root, new ArrayList<>());
    } catch (Exception error) {
      return true;
    }
  }

  /**
   * A page field has a cached result in w:t, but that result is not ordinary
   * footer prose.  Track complex fields while walking the XML so PAGE fields
   * are excluded while other fields and literal footer text remain visible.
   */
  private static boolean containsOrdinaryText(Node node, List<FieldState> fields) {
    if (node instanceof Element element) {
      String localName = element.getLocalName();
      if ("fldSimple".equals(localName)) {
        String instruction = element.getAttributeNS(namespace(element), "instr");
        if (instruction != null && instruction.toUpperCase(Locale.ROOT).contains("PAGE")) return false;
      }
      if ("fldChar".equals(localName)) {
        String type = element.getAttributeNS(namespace(element), "fldCharType");
        if ("begin".equalsIgnoreCase(type)) fields.add(new FieldState());
        else if ("separate".equalsIgnoreCase(type) && !fields.isEmpty()) fields.get(fields.size() - 1).separated = true;
        else if ("end".equalsIgnoreCase(type) && !fields.isEmpty()) fields.remove(fields.size() - 1);
      } else if ("instrText".equals(localName) && !fields.isEmpty()) {
        FieldState field = fields.get(fields.size() - 1);
        if (!field.separated) field.instruction.append(element.getTextContent());
      } else if ("t".equals(localName)) {
        String value = element.getTextContent();
        boolean pageResult = fields.stream().anyMatch(field -> field.separated
            && field.instruction.toString().toUpperCase(Locale.ROOT).contains("PAGE"));
        if (!pageResult && value != null && !value.isBlank()) return true;
      }
    }
    for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
      if (containsOrdinaryText(child, fields)) return true;
    return false;
  }

  private static final class FieldState {
    private final StringBuilder instruction = new StringBuilder();
    private boolean separated;
  }

  private static boolean hasNoteText(byte[] xml, String noteName) {
    try {
      Element root = cn.luwneshacha.thesis.document.XmlPartSupport.parse(xml).getDocumentElement();
      var notes = root.getElementsByTagNameNS(namespace(root), noteName);
      for (int index = 0; index < notes.getLength(); index++) {
        Element note = (Element) notes.item(index);
        String noteType = note.getAttributeNS(namespace(root), "type");
        if ("separator".equals(noteType) || "continuationSeparator".equals(noteType)) continue;
        var texts = note.getElementsByTagNameNS(namespace(root), "t");
        for (int textIndex = 0; textIndex < texts.getLength(); textIndex++)
          if (!texts.item(textIndex).getTextContent().isBlank()) return true;
      }
      return false;
    } catch (Exception error) {
      return true;
    }
  }

  private static boolean hasElement(Element root, String localName) {
    return root.getElementsByTagNameNS(namespace(root), localName).getLength() > 0;
  }

  private static boolean hasActiveBodyGraphic(Element root) {
    var bodies = root.getElementsByTagNameNS(W, "body");
    return bodies.getLength() > 0 && hasActiveGraphic((Element) bodies.item(0));
  }

  private static boolean hasActiveGraphic(Element element) {
    if (isNonVisibleRevision(element)) return false;
    if (W.equals(element.getNamespaceURI()) && List.of("drawing", "pict", "object")
        .contains(element.getLocalName())) return true;
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
      if (child instanceof Element nested && hasActiveGraphic(nested)) return true;
    return false;
  }

  private static void reportUnparsedTables(List<UncheckedRegion> unchecked, Element root,
      String partName, List<cn.luwneshacha.thesis.document.TableModel> parsedTables) {
    List<List<Integer>> xmlPaths = new ArrayList<>();
    collectTablePaths(root, List.of(), xmlPaths);
    var parsedPaths = parsedTables.stream().map(table -> table.source().elementPath())
        .collect(java.util.stream.Collectors.toSet());
    for (List<Integer> path : xmlPaths) {
      if (parsedPaths.contains(path)) continue;
      unchecked.add(new UncheckedRegion(partName, "表格 XML 节点 " + path,
          CoverageKind.NOT_INSPECTED,
          "文档 XML 中存在表格节点，但解析结果没有对应表格模型；该表格的格式规则未运行",
          "请在 Word 中检查该表格是否位于兼容模式、修订或特殊嵌套结构中，再另存为标准 .docx 后重新检查"));
    }
  }

  private static void collectTablePaths(Element element, List<Integer> path, List<List<Integer>> paths) {
    if (isNonVisibleRevision(element)) return;
    if (W.equals(element.getNamespaceURI()) && "tbl".equals(element.getLocalName())) paths.add(path);
    int index = 0;
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (!(child instanceof Element childElement)) continue;
      var childPath = new ArrayList<>(path);
      childPath.add(index++);
      collectTablePaths(childElement, List.copyOf(childPath), paths);
    }
  }

  private static boolean hasRevision(Element root) {
    String namespace = namespace(root);
    for (String localName : List.of("ins", "del", "moveFrom", "moveTo"))
      if (root.getElementsByTagNameNS(namespace, localName).getLength() > 0) return true;
    return false;
  }

  private static String namespace(Element root) {
    String namespace = root.getNamespaceURI();
    return namespace == null || namespace.isBlank() ? W : namespace;
  }

  public record Coverage(List<RuleCoverage> rules, List<UncheckedRegion> uncheckedRegions) {
    public Coverage { rules = List.copyOf(rules); uncheckedRegions = List.copyOf(uncheckedRegions); }
  }
}
