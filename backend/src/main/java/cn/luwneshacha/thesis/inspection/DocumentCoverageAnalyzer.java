package cn.luwneshacha.thesis.inspection;

import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RawDocx;
import cn.luwneshacha.thesis.rules.CheckResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Reports document regions that current XML parsing or annotation does not cover. */
public final class DocumentCoverageAnalyzer {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String STRICT_W = "http://purl.oclc.org/ooxml/wordprocessingml/main";

  public Coverage analyze(RawDocx raw, ParsedDocument parsed, List<CheckResult> results) {
    var unchecked = new ArrayList<UncheckedRegion>();
    var coveredParts = new java.util.HashSet<String>();
    for (var paragraph : parsed.paragraphs()) coveredParts.add(paragraph.source().partName());
    for (var table : parsed.tables()) coveredParts.add(table.source().partName());
    for (var equation : parsed.equations()) coveredParts.add(equation.source().partName());
    for (var image : parsed.images()) coveredParts.add(image.source().partName());
    for (var pageNumber : parsed.pageNumbers()) coveredParts.add(pageNumber.source().partName());
    for (var header : parsed.headers()) coveredParts.add(header.source().partName());

    for (var part : raw.parts().values()) {
      if (!part.xml()) continue;
      String name = part.name();
      String lower = name.toLowerCase(Locale.ROOT);
      if (isHeaderOrFooter(lower)) {
        if (lower.contains("header") && coveredParts.contains(name)) continue;
        // Page-number parsing marks a footer part as covered, but it does not
        // cover unrelated prose in the same part. Evaluate the XML content
        // independently so a PAGE field cannot hide ordinary header/footer text.
        if (hasOrdinaryText(part.bytes())) {
          if (lower.contains("header")) {
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
      if (isWordTextPart(lower)) {
        try {
          Element root = cn.luwneshacha.thesis.document.XmlPartSupport.parse(part.bytes()).getDocumentElement();
          if ("word/document.xml".equals(lower))
            reportUnparsedTables(unchecked, root, name, parsed.tables());
          if (hasElement(root, "txbxContent"))
            addInspectionAndAnnotationGaps(unchecked, name, "文本框文字",
                "文本框中的文字未纳入段落格式检查",
                "当前批注写入器不能保证批注准确锚定到文本框文字",
                "请将需要自动检查的文字移出文本框，或按正文格式要求单独核对",
                "本次不能在文本框文字上准确放置批注；请按报告说明修改文本框内容");
          if (hasRevision(root))
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
    return new Coverage(new RuleCoverageAnalyzer().analyzeWithCatalog(results), List.copyOf(unchecked));
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
