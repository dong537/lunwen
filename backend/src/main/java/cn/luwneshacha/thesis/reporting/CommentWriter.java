package cn.luwneshacha.thesis.reporting;

import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.CheckResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public final class CommentWriter {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
  private static final String CT = "http://schemas.openxmlformats.org/package/2006/content-types";
  private static final String COMMENTS_TYPE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments";
  private static final String STRICT_W = "http://purl.oclc.org/ooxml/wordprocessingml/main";
  private static final String STRICT_COMMENTS_TYPE = "http://purl.oclc.org/ooxml/officeDocument/relationships/comments";

  public AnnotationReport writeCopy(Path source, Path target, List<CheckResult> results) {
    return writeCopy(source, target, null, results);
  }

  public AnnotationReport writeCopy(Path source, Path target, String expectedSourceSha256, List<CheckResult> results) {
    List<AnnotationItem> rejected = new ArrayList<>();
    try {
      byte[] sourceBytes = Files.readAllBytes(source);
      String actualHash = sha256(sourceBytes);
      if (expectedSourceSha256 != null && !expectedSourceSha256.equalsIgnoreCase(actualHash)) {
        for (CheckResult result : results) rejected.add(new AnnotationItem(result.ruleId(), AnnotationStatus.REJECTED, "SOURCE_HASH_MISMATCH", result.source()));
        return new AnnotationReport(rejected, false);
      }
      Map<String, byte[]> parts = readParts(source);
      Document document = parse(parts.get("word/document.xml"));
      boolean strict = isStrictDocument(document);
      int nextId = nextCommentId(parts.get("word/comments.xml"));
      Document comments = parts.containsKey("word/comments.xml") ? parse(parts.get("word/comments.xml")) : newCommentsDocument(strict);
      Element commentsRoot = comments.getDocumentElement();
      List<CheckResult> written = new ArrayList<>();
      List<AnnotationItem> writtenItems = new ArrayList<>();
      Map<String, List<CheckResult>> grouped = new LinkedHashMap<>();
      for (CheckResult result : results) {
        if (!"word/document.xml".equals(result.source().partName())) {
          rejected.add(new AnnotationItem(result.ruleId(), AnnotationStatus.UNSUPPORTED_ANCHOR, "PART_NOT_SUPPORTED", result.source()));
          continue;
        }
        grouped.computeIfAbsent(result.source().elementPath().toString(), ignored -> new ArrayList<>()).add(result);
      }
      for (List<CheckResult> group : grouped.values()) {
        CheckResult result = group.get(0);
        try {
          Element paragraph = resolve(document, result.source());
          String wordNamespace = paragraph.getNamespaceURI();
          if ("tbl".equals(paragraph.getLocalName()) && wordNamespace != null) {
            var nested = paragraph.getElementsByTagNameNS(wordNamespace, "p");
            if (nested.getLength() == 0) throw new AnchorException("TABLE_HAS_NO_PARAGRAPH");
            paragraph = (Element) nested.item(0);
            wordNamespace = paragraph.getNamespaceURI();
          }
          if (!"p".equals(paragraph.getLocalName()) || wordNamespace == null) throw new AnchorException("ANCHOR_NOT_PARAGRAPH");
          String text = paragraph.getTextContent();
          boolean hasDrawing = paragraph.getElementsByTagNameNS(wordNamespace, "drawing").getLength() > 0;
          if ((text == null || text.isBlank()) && !hasDrawing) throw new AnchorException("EMPTY_ANCHOR");
          highlight(paragraph, result.ruleId());
          addRange(paragraph, nextId);
          // 一个段落只插入一个批注；颜色取该段落最先发现的错误类型，批注正文会列出其余问题。
          commentsRoot.appendChild(comment(comments, nextId, group));
          for (CheckResult item : group) {
            written.add(item);
            writtenItems.add(new AnnotationItem(item.ruleId(), AnnotationStatus.WRITTEN, "", item.source()));
          }
          nextId++;
        } catch (AnchorException error) {
          for (CheckResult item : group) rejected.add(new AnnotationItem(item.ruleId(), AnnotationStatus.UNSUPPORTED_ANCHOR, error.getMessage(), item.source()));
        }
      }
      if (written.isEmpty()) return new AnnotationReport(rejected, false);
      parts.put("word/document.xml", serialize(document));
      parts.put("word/comments.xml", serialize(comments));
      parts.put("word/_rels/document.xml.rels", addRelationship(parts.get("word/_rels/document.xml.rels"), strict));
      parts.put("[Content_Types].xml", addContentType(parts.get("[Content_Types].xml")));
      writeParts(target, parts);
      rejected.addAll(writtenItems);
      return new AnnotationReport(rejected, true);
    } catch (IOException | RuntimeException error) {
      try { if (target != null) Files.deleteIfExists(target); }
      catch (IOException cleanupError) { error.addSuppressed(cleanupError); }
      for (CheckResult result : results) {
        boolean alreadyReported = rejected.stream().anyMatch(item ->
            item.ruleId().equals(result.ruleId()) && item.source().equals(result.source()));
        if (!alreadyReported)
          rejected.add(new AnnotationItem(result.ruleId(), AnnotationStatus.REJECTED, "WRITE_FAILED", result.source()));
      }
      return new AnnotationReport(rejected, false);
    }
  }

  private static Map<String, byte[]> readParts(Path source) throws IOException {
    Map<String, byte[]> parts = new LinkedHashMap<>();
    try (ZipFile zip = new ZipFile(source.toFile())) {
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (!entry.isDirectory()) parts.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
      }
    }
    return parts;
  }

  private static void writeParts(Path target, Map<String, byte[]> parts) throws IOException {
    Files.deleteIfExists(target);
    try (var output = Files.newOutputStream(target); var zip = new ZipOutputStream(output)) {
      for (var part : parts.entrySet()) {
        zip.putNextEntry(new ZipEntry(part.getKey()));
        zip.write(part.getValue());
        zip.closeEntry();
      }
    }
  }

  private static Element resolve(Document document, SourceAddress address) throws AnchorException {
    Element current = document.getDocumentElement();
    for (Integer index : address.elementPath()) {
      List<Element> children = elementChildren(current);
      if (index < 0 || index >= children.size()) throw new AnchorException("PATH_OUT_OF_RANGE");
      current = children.get(index);
    }
    return current;
  }

  private static void addRange(Element paragraph, int id) throws AnchorException {
    List<Element> children = elementChildren(paragraph);
    Element firstContent = children.stream().filter(child -> !"pPr".equals(child.getLocalName())).findFirst().orElse(null);
    Element lastContent = null;
    for (Element child : children) if (!"pPr".equals(child.getLocalName())) lastContent = child;
    if (firstContent == null || lastContent == null) throw new AnchorException("EMPTY_ANCHOR");
    Document doc = paragraph.getOwnerDocument();
    String wordNamespace = paragraph.getNamespaceURI();
    Element start = doc.createElementNS(wordNamespace, "w:commentRangeStart");
    start.setAttributeNS(wordNamespace, "w:id", String.valueOf(id));
    Element end = doc.createElementNS(wordNamespace, "w:commentRangeEnd");
    end.setAttributeNS(wordNamespace, "w:id", String.valueOf(id));
    paragraph.insertBefore(start, firstContent);
    paragraph.insertBefore(end, lastContent.getNextSibling());
    Element referenceRun = doc.createElementNS(wordNamespace, "w:r");
    Element reference = doc.createElementNS(wordNamespace, "w:commentReference");
    reference.setAttributeNS(wordNamespace, "w:id", String.valueOf(id));
    referenceRun.appendChild(reference);
    paragraph.appendChild(referenceRun);
  }

  private static void highlight(Element paragraph, String ruleId) {
    String color = switch (ruleId) {
      case "BODY-SIZE" -> "yellow";
      case "BODY-FONT" -> "cyan";
      case "BODY-INDENT" -> "green";
      case "ABSTRACT-FONT" -> "cyan";
      case "ABSTRACT-SIZE" -> "yellow";
      case "ABSTRACT-INDENT" -> "green";
      case "ABSTRACT-LINE" -> "darkMagenta";
      case "ZH-01-SPACING", "ZH-02-BLANK", "ZH-04-BLANK", "EN-01-BLANK", "EN-03-BLANK" -> "magenta";
      case "ZH-06-LENGTH" -> "red";
      case "EN-03-FORMAT" -> "magenta";
      case "EN-06-LENGTH" -> "red";
      case "ABSTRACT-KEYWORD-BOLD" -> "green";
      case "ABSTRACT-KEYWORD-FORMAT" -> "magenta";
      case "PAGE-01" -> "magenta";
      case "PAGE-05" -> "yellow";
      case "PAGE-04" -> "lightGray";
      case "PAGE-03" -> "lightGray";
      case "HEAD-02-BLANK" -> "magenta";
      case "HEADING-LINE" -> "darkMagenta";
      case "TOC-01-BLANK", "TOC-01-SPACING", "TOC-SPACING" -> "magenta";
      case "TOC-01-LINE" -> "darkMagenta";
      case "HEAD-01-SPACING", "HEAD-02-SPACING", "HEAD-03-SPACING" -> "magenta";
      case "LENGTH-01" -> "red";
      case "HEAD-03-INDENT", "HEAD-05-INDENT" -> "green";
      case "HEAD-05-FONT" -> "cyan";
      case "HEAD-05-SIZE", "HEAD-05-LINE" -> "yellow";
      case "FIG-01" -> "red";
      case "FIG-03" -> "yellow";
      case "TAB-03" -> "yellow";
      case "EQ-02" -> "yellow";
      case "EQ-04" -> "red";
      case "EQ-05" -> "yellow";
      case "FIG-02" -> "lightGray";
      case "REF-FONT" -> "cyan";
      case "REF-SIZE" -> "yellow";
      case "REF-LINE" -> "darkMagenta";
      case "REF-03" -> "magenta";
      case "REF-05" -> "red";
      case "APP-01" -> "red";
      case "CAPTION-FONT" -> "cyan";
      case "CAPTION-SIZE" -> "yellow";
      case "HEADING-FONT" -> "cyan";
      case "HEADING-SIZE" -> "yellow";
      case "COVER-01-FONT", "COVER-02-FONT", "COVER-03-FONT", "COVER-04-FONT" -> "cyan";
      case "COVER-01-SIZE", "COVER-02-SIZE", "COVER-03-SIZE", "COVER-04-SIZE" -> "yellow";
      case "COVER-01-ALIGNMENT" -> "magenta";
      case "COVER-03-BOLD", "COVER-04-BOLD" -> "green";
      case "ZH-01-FONT", "EN-01-FONT", "TOC-01-FONT", "REF-01-FONT", "END-01-FONT" -> "cyan";
      case "ZH-01-SIZE", "EN-01-SIZE", "TOC-01-SIZE", "REF-01-SIZE", "END-01-SIZE", "EN-01-BOLD" -> "yellow";
      case "ZH-01-ALIGNMENT", "EN-01-ALIGNMENT", "TOC-01-ALIGNMENT", "REF-01-ALIGNMENT", "END-01-ALIGNMENT" -> "magenta";
      default -> "lightGray";
    };
    Document doc = paragraph.getOwnerDocument();
    String wordNamespace = paragraph.getNamespaceURI();
    var runs = paragraph.getElementsByTagNameNS(wordNamespace, "r");
    for (int i = 0; i < runs.getLength(); i++) {
      Element run = (Element) runs.item(i);
      Element properties = null;
      for (Node node = run.getFirstChild(); node != null; node = node.getNextSibling()) {
        if (node instanceof Element child && "rPr".equals(child.getLocalName())) { properties = child; break; }
      }
      if (properties == null) { properties = doc.createElementNS(wordNamespace, "w:rPr"); run.insertBefore(properties, run.getFirstChild()); }
      var existingHighlights = properties.getElementsByTagNameNS(wordNamespace, "highlight");
      while (existingHighlights.getLength() > 0) properties.removeChild(existingHighlights.item(0));
      Element mark = doc.createElementNS(wordNamespace, "w:highlight");
      mark.setAttributeNS(wordNamespace, "w:val", color);
      properties.appendChild(mark);
    }
  }

  private static Element comment(Document comments, int id, List<CheckResult> results) {
    String wordNamespace = comments.getDocumentElement().getNamespaceURI();
    Element c = comments.createElementNS(wordNamespace, "w:comment");
    c.setAttributeNS(wordNamespace, "w:id", String.valueOf(id));
    c.setAttributeNS(wordNamespace, "w:author", "\u8bba\u6587\u683c\u5f0f\u7b5b\u67e5");
    c.setAttributeNS(wordNamespace, "w:date", Instant.now().toString());
    Element p = comments.createElementNS(wordNamespace, "w:p");
    Element r = comments.createElementNS(wordNamespace, "w:r");
    Element t = comments.createElementNS(wordNamespace, "w:t");
    t.setTextContent(results.stream().map(CommentWriter::suggestion).distinct().collect(java.util.stream.Collectors.joining("\n")));
    r.appendChild(t); p.appendChild(r); c.appendChild(p);
    return c;
  }

  private static String suggestion(CheckResult result) {
    return switch (result.ruleId()) {
      case "CITE-01" -> "CITATION_FORMAT_UNKNOWN".equals(result.reasonCode())
          ? "请把这处引用改为小四 Times New Roman，并设置为上标。"
          : "引用编号请设为小四 Times New Roman 上标。";
      case "PAGE-01" -> "页边距不符合要求，请按规范调整为 2.6 cm。";
      case "PAGE-05" -> "页码格式不符合要求，请检查页脚居中、五号 Times New Roman。";
      case "PAGE-04" -> "正文页码请统一改为阿拉伯数字。";
      case "PAGE-03" -> "请在本章标题段落设置“段前分页”，让章节从新页开始。";
      case "HEAD-02-BLANK" -> "章节标题和正文之间空一行。";
      case "TOC-01-SPACING" -> "目录标题改为“目    录”，中间保留 4 个空格。";
      case "TOC-01-BLANK" -> "目录标题和目录内容之间空一行。";
      case "TOC-01-LINE" -> "目录标题：段落行距调整为 1.5 倍。";
      case "TOC-SPACING" -> "目录编号后按层级保留规定空格：章标题空 2 格，节标题空 1 格。";
      case "HEAD-01-SPACING" -> "一级标题：序数后空 2 格，再写标题。";
      case "HEAD-02-SPACING", "HEAD-03-SPACING" -> "本级标题：序数后空 1 格，再写标题。";
      case "LENGTH-01" -> "正文不少于 8000 个汉字，请补充正文内容。";
      case "HEAD-03-INDENT" -> "三级标题：首行缩进调整为 2 字符。";
      case "HEAD-05-FONT" -> "三级以下标题：中文改为宋体，字母和数字改为 Times New Roman。";
      case "HEAD-05-SIZE" -> "三级以下标题：字号调整为小四号。";
      case "HEAD-05-LINE" -> "三级以下标题：段落行距调整为 1.5 倍。";
      case "HEAD-05-INDENT" -> "三级以下标题：首行缩进调整为 2 字符。";
      case "BODY-FONT" -> "正文：中文改为宋体，字母和数字改为 Times New Roman。";
      case "BODY-SIZE" -> "正文：字号调整为小四号。";
      case "BODY-INDENT" -> "首行缩进不符合要求，请调整为 2 字符。";
      case "ABSTRACT-FONT" -> "摘要字体不符合要求，请检查中英文字符字体。";
      case "ABSTRACT-SIZE" -> "摘要字号应为小四，请检查。";
      case "ABSTRACT-INDENT" -> "摘要首行缩进应为 2 字符，请调整。";
      case "ABSTRACT-KEYWORD-BOLD" -> "“关键词”标签需要加粗，后面的词不用加粗。";
      case "ABSTRACT-KEYWORD-FORMAT" -> "关键词格式不符合要求，请检查冒号、数量和分号。";
      case "REF-FONT" -> "参考文献：中文改为宋体，字母和数字改为 Times New Roman。";
      case "REF-SIZE" -> "参考文献：字号调整为小四号。";
      case "REF-LINE" -> "参考文献行距不是 1.5 倍，请调整段落行距。";
      case "REF-03" -> switch (result.reasonCode()) {
        case "REFERENCE_ORDER_MISMATCH" -> "请按正文第一次出现的先后重新排列文末参考文献。";
        case "REFERENCE_NOT_CITED" -> "这条参考文献未在正文中找到对应引用。";
        case "CITED_REFERENCE_MISSING" -> "正文引用编号在文末参考文献中没有对应条目。";
        case "CITATION_RANGE_REVIEW" -> "请把这组引用改成连续编号，并为每个编号补上对应的文末条目。";
        default -> "请删除重复的参考文献编号，并按正文引用顺序重新排列。";
      };
      case "REF-05" -> "参考文献少于 10 篇，请补充有效文献。";
      case "APP-01" -> switch (result.reasonCode()) {
        case "APPENDIX_SEQUENCE_MISMATCH" -> "附录编号应从 A 开始并按字母连续排列。";
        case "APPENDIX_OBJECT_PREFIX_MISSING" -> "附录中的图、表或公式编号请补上本附录字母。";
        default -> "附录中的图、表或公式编号字母应与本附录一致。";
      };
      case "CAPTION-FONT" -> "图表题字体不符合要求，请按规范使用宋体。";
      case "CAPTION-SIZE" -> "图表题字号不符合要求，请按规范调整为五号。";
      case "ABSTRACT-LINE" -> "摘要行距不是 1.5 倍，请调整段落行距。";
      case "ZH-01-SPACING" -> "摘要标题改为“摘  要”，中间保留 4 个空格。";
      case "ZH-02-BLANK", "EN-01-BLANK" -> "摘要标题下方空一行，再开始摘要正文。";
      case "ZH-04-BLANK", "EN-03-BLANK" -> "摘要正文和关键词之间空一行。";
      case "ZH-06-LENGTH" -> "中文摘要控制在 300 个汉字以内，请删减重复内容。";
      case "EN-03-FORMAT" -> "英文关键词：使用 Keywords: 开头，关键词之间用英文分号（;）分隔。";
      case "EN-06-LENGTH" -> "英文摘要控制在 250 个实词以内，请删减重复内容。";
      case "HEADING-FONT" -> "本级标题：中文改为黑体，字母和数字改为 Times New Roman。";
      case "HEADING-SIZE" -> headingSizeSuggestion(result);
      case "HEADING-LINE" -> "二级标题：段落行距调整为 1.5 倍。";
      case "COVER-01-FONT" -> "学校名称：改为方正小标宋简体。";
      case "COVER-01-SIZE" -> "学校名称：字号调整为小初号。";
      case "COVER-01-ALIGNMENT" -> "学校名称：设置为居中。";
      case "COVER-02-FONT" -> "论文题目：改为黑体。";
      case "COVER-02-SIZE" -> "论文题目：字号调整为小二号。";
      case "COVER-03-FONT" -> "学生姓名、学号、院系、专业年级、指导教师：统一改为仿宋。";
      case "COVER-03-SIZE" -> "学生姓名、学号、院系、专业年级、指导教师：字号统一调整为三号。";
      case "COVER-03-BOLD" -> "学生姓名、学号、院系、专业年级、指导教师：统一加粗。";
      case "COVER-04-FONT" -> "日期：改为仿宋。";
      case "COVER-04-SIZE" -> "日期：字号调整为三号。";
      case "COVER-04-BOLD" -> "日期：统一加粗。";
      case "ZH-01-FONT" -> "摘要标题字体不符合要求，请检查黑体设置。";
      case "ZH-01-SIZE" -> "摘要标题字号应为三号，请调整。";
      case "ZH-01-ALIGNMENT" -> "摘要标题需要居中。";
      case "EN-01-FONT" -> "ABSTRACT 标题字体应为 Times New Roman。";
      case "EN-01-SIZE" -> "ABSTRACT 标题字号应为三号。";
      case "EN-01-BOLD" -> "ABSTRACT 标题需要加粗。";
      case "EN-01-ALIGNMENT" -> "ABSTRACT 标题需要居中。";
      case "TOC-01-FONT" -> "目录标题字体不符合要求，请检查黑体设置。";
      case "TOC-01-SIZE" -> "目录标题字号应为三号，请调整。";
      case "TOC-01-ALIGNMENT" -> "目录标题需要居中。";
      case "REF-01-FONT" -> "参考文献标题字体不符合要求，请检查黑体设置。";
      case "REF-01-SIZE" -> "参考文献标题字号应为三号，请调整。";
      case "REF-01-ALIGNMENT" -> "参考文献标题需要居中。";
      case "END-01-FONT" -> "致谢或附录标题字体不符合要求，请检查黑体设置。";
      case "END-01-SIZE" -> "致谢或附录标题字号应为三号，请调整。";
      case "END-01-ALIGNMENT" -> "致谢或附录标题需要居中。";
      case "END-01-SPACING" -> "致谢标题改为“致    谢”，中间保留 4 个空格。";
      case "REF-01-BLANK", "END-01-BLANK" -> "标题下方空一行，再开始正文。";
      case "TAB-01" -> "正文里没有找到这张表的引用，请在相关位置补一句。";
      case "TAB-02" -> "表格请改为三线表：保留顶线、表头下横线和底线，去掉竖线。";
      case "TAB-03" -> switch (result.reasonCode()) {
        case "TABLE_CAPTION_MISSING" -> "表格缺少表题，请补充表号和题名。";
        case "TABLE_CAPTION_BELOW" -> "表题在表格下方，请移到表格上方。";
        case "TABLE_CAPTION_NUMBER_FORMAT" -> "表号和表题之间少了一个空格，或编号格式不符合要求。";
        default -> "请把表题移到表格上方，并与表格保持紧邻。";
      };
      case "TAB-04" -> "TABLE_UNITS_MISSING".equals(result.reasonCode())
          ? "表头缺少量名称或单位，请在列名后用括号补充单位。"
          : "请在表头补充量名称和单位，单位放在括号内。";
      case "TAB-06" -> "表格中不要用“同前”“同左”代替内容，请重复写出完整文字。";
      case "FIG-01" -> "FIGURE_REFERENCE_NEEDS_REVIEW".equals(result.reasonCode())
          ? "请在正文中写出明确图号，例如“如图 2-1 所示”，不要只写“如图所示”。"
          : "正文里没有找到这幅图的引用，请在相关位置补一句。";
      case "FIG-03" -> "图号和图题之间少了一个空格。";
      case "FIG-02" -> "请将图片和图题设为居中，图题放在图片下方。";
      case "FIG-04" -> "IMAGE_ASPECT_RATIO_MISMATCH".equals(result.reasonCode())
          ? "图片被拉伸了，请锁定宽高比后重新调整大小。"
          : "图片尺寸无法识别，请重新插入图片并保留有效尺寸。";
      case "EQ-01" -> "公式必须编号，并按一级标题编排，例如第三章第 1 个公式写作（3-1）。";
      case "EQ-02" -> "公式编号的字体或字号不符合要求，请改为五号 Times New Roman。";
      case "EQ-03" -> "公式整体居中，公式编号右对齐，并与公式保持同一行。";
      case "EQ-04" -> "正文里没有找到这条公式的引用，请在相关位置补充说明。";
      case "EQ-05" -> "EQUATION_NUMBER_SEQUENCE_MISMATCH".equals(result.reasonCode())
          ? "公式编号应按本章连续排列，例如 3-1、3-2、3-3。"
          : "公式编号重复，请检查编号顺序。";
      case "EQ-06" -> "请将公式转换为 Word 公式，并统一公式字体和字号。";
      case "EQ-07" -> "请在公式下方补充变量含义和单位说明。";
      case "EQ-08" -> "公式字体应统一为 Times New Roman，请检查公式内部字体设置。";
      case "EQ-09" -> "公式变量应使用斜体，请取消变量的正体设置。";
      case "REF-02" -> "参考文献缺少类型标识，请补充 [M]、[J] 等类别。";
      case "REF-06" -> "参考文献缺少" + result.actual() + "，请按规范补齐作者、题名、来源或年份。";
      case "REF-07" -> "参考文献编号应从 1 开始连续排列，请检查跳号或编号格式。";
      case "REF-04" -> "请按学校规定补齐作者、题名、来源、年份和页码等字段，并统一标点格式。";
      default -> "请按学校规范修改此处格式，并与同级内容保持一致。";
    };
  }

  private static String headingSizeSuggestion(CheckResult result) {
    String expected = result.expected();
    return switch (expected) {
      case "32" -> "一级标题：字号调整为三号（16 磅）。";
      case "28" -> "二级标题：字号调整为四号（14 磅）。";
      case "24" -> "三级标题：字号调整为小四号（12 磅）。";
      default -> "本级标题：按标题层级调整字号。";
    };
  }

  private static byte[] addRelationship(byte[] bytes, boolean strict) throws IOException {
    Document doc = parse(bytes);
    Element root = doc.getDocumentElement();
    var existing = root.getElementsByTagNameNS(REL, "Relationship");
    for (int i = 0; i < existing.getLength(); i++) {
      String type = ((Element) existing.item(i)).getAttribute("Type");
      if (COMMENTS_TYPE.equals(type) || STRICT_COMMENTS_TYPE.equals(type)) return serialize(doc);
    }
    int max = 0;
    var nodes = existing;
    for (int i = 0; i < nodes.getLength(); i++) {
      String id = ((Element) nodes.item(i)).getAttribute("Id");
      if (id.startsWith("rId")) try { max = Math.max(max, Integer.parseInt(id.substring(3))); } catch (NumberFormatException ignored) { }
    }
    Element relationship = doc.createElementNS(REL, "Relationship");
    relationship.setAttribute("Id", "rId" + (max + 1));
    relationship.setAttribute("Type", strict ? STRICT_COMMENTS_TYPE : COMMENTS_TYPE);
    relationship.setAttribute("Target", "comments.xml");
    root.appendChild(relationship);
    return serialize(doc);
  }

  private static byte[] addContentType(byte[] bytes) throws IOException {
    Document doc = parse(bytes);
    Element root = doc.getDocumentElement();
    var overrides = root.getElementsByTagNameNS(CT, "Override");
    for (int i = 0; i < overrides.getLength(); i++) {
      if ("/word/comments.xml".equals(((Element) overrides.item(i)).getAttribute("PartName"))) return serialize(doc);
    }
    Element override = doc.createElementNS(CT, "Override");
    override.setAttribute("PartName", "/word/comments.xml");
    override.setAttribute("ContentType", "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml");
    root.appendChild(override);
    return serialize(doc);
  }

  private static int nextCommentId(byte[] bytes) throws IOException {
    if (bytes == null) return 0;
    Document comments = parse(bytes);
    String wordNamespace = comments.getDocumentElement().getNamespaceURI();
    var nodes = comments.getDocumentElement().getElementsByTagNameNS(wordNamespace, "comment");
    int max = -1;
    for (int i = 0; i < nodes.getLength(); i++) max = Math.max(max, Integer.parseInt(((Element) nodes.item(i)).getAttributeNS(W, "id")));
    return max + 1;
  }

  private static Document newCommentsDocument(boolean strict) {
    Document doc = newDocument();
    Element root = doc.createElementNS(strict ? STRICT_W : W, "w:comments");
    doc.appendChild(root);
    return doc;
  }

  private static boolean isStrictDocument(Document document) {
    return STRICT_W.equals(document.getDocumentElement().getNamespaceURI());
  }

  private static Document parse(byte[] bytes) throws IOException {
    try {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    } catch (Exception error) { throw new IOException("invalid XML part", error); }
  }

  private static Document newDocument() {
    try { return DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument(); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  private static byte[] serialize(Document doc) throws IOException {
    try {
      var transformer = TransformerFactory.newInstance().newTransformer();
      transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
      transformer.setOutputProperty(OutputKeys.INDENT, "no");
      transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
      var output = new StringWriter();
      transformer.transform(new DOMSource(doc), new StreamResult(output));
      return output.toString().getBytes(StandardCharsets.UTF_8);
    } catch (Exception error) { throw new IOException("cannot serialize XML", error); }
  }

  private static List<Element> elementChildren(Element element) {
    List<Element> children = new ArrayList<>();
    for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) if (node instanceof Element child) children.add(child);
    return children;
  }

  private static String sha256(byte[] bytes) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  private static final class AnchorException extends Exception {
    AnchorException(String message) { super(message); }
  }
}
