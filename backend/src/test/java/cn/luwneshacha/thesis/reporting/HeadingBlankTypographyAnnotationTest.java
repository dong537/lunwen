package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.Applicability;
import cn.luwneshacha.thesis.rules.ExecutionStatus;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.Judgement;
import cn.luwneshacha.thesis.rules.RuleEngine;
import cn.luwneshacha.thesis.rules.RuleSet;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class HeadingBlankTypographyAnnotationTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final Set<String> RULE_IDS = Set.of("HEAD-02-BLANK-FONT", "HEAD-02-BLANK-SIZE");
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(strings = {"摘    要", "目    录", "参考文献", "致    谢", "附    录", "第二章  系统设计"})
  void namesOriginalHeadingAndActualBlankFormatEvenWhenCommentMovesToPageStart(String title)
      throws Exception {
    Path source = fixture("paragraphMark", title);
    var raw = new DocxReader().read(source);
    var findings = blankTypographyFindings(source);
    assertEquals(2, findings.size(), findings.toString());
    Path output = temp.resolve("context-annotated.docx");
    assertWritten(new CommentWriter().writeCopy(source, output, raw.sourceSha256(), findings));

    String text = read(output, "word/comments.xml").getDocumentElement().getTextContent();
    assertTrue(text.contains("“" + title + "”标题后的空行"), text);
    assertTrue(text.startsWith("本条检查标题下方的空白行，不是标题文字。"), text);
    assertEquals(1, text.split("原位置：", -1).length - 1, text);
    assertTrue(text.contains("当前中文字体为宋体"), text);
    assertTrue(text.contains("当前字号为12.0 磅"), text);
    assertTrue(text.contains("黑体"), text);
    assertTrue(text.contains("三号（16 磅）"), text);
    assertTrue(text.contains("批注显示在本页页首"), text);
    assertTrue(text.contains("不是该位置的标题或正文"), text);
    assertFalse(text.contains("问题原位置没有可圈定的文字"), text);
    assertTrue(text.contains("打开“显示段落标记”"), text);
    assertTrue(text.contains("选中整个空白行（包括其中的空格、换行标记和段落标记）"), text);
    assertTrue(bodyWithoutCommentAnchors(source).isEqualNode(bodyWithoutCommentAnchors(output)));
  }

  @Test
  void doesNotClaimTheHeadingIsCorrectWhenItsTypographyAlsoFails() throws Exception {
    Path source = fixture("paragraphMark", "第六章  系统测试", "宋体", 24, "宋体", 24);
    var parsed = new Docx4jDocumentParser().parse(new DocxReader().read(source));
    var findings = new RuleEngine().check(parsed, RuleSet.schoolV1()).stream()
        .filter(item -> RULE_IDS.contains(item.ruleId()) || "HEADING-FONT".equals(item.ruleId())
            || "HEADING-SIZE".equals(item.ruleId()))
        .filter(item -> item.judgement() == Judgement.FAIL).toList();
    assertTrue(findings.stream().anyMatch(item -> "HEADING-FONT".equals(item.ruleId())));
    assertTrue(findings.stream().anyMatch(item -> "HEADING-SIZE".equals(item.ruleId())));
    Path output = temp.resolve("wrong-heading-and-blank.docx");

    var report = new CommentWriter().writeCopy(source, output, findings);

    assertTrue(report.outputWritten(), report.items().toString());
    assertEquals(findings.size(), report.items().size());
    assertTrue(report.items().stream().allMatch(item -> item.status() == AnnotationStatus.WRITTEN));
    String text = read(output, "word/comments.xml").getDocumentElement().getTextContent();
    assertTrue(text.contains("本条检查标题下方的空白行，不是标题文字。"), text);
    assertFalse(text.contains("标题文字格式正确"), text);
    assertFalse(text.contains("标题字体、字号正确"), text);
    assertFalse(text.contains("无需修改标题文字"), text);
    Element annotatedBody = bodyWithoutCommentAnchors(output);
    assertEquals(visibleText(bodyWithoutCommentAnchors(source)), visibleText(annotatedBody));
    assertEquals(1, annotatedBody.getElementsByTagNameNS(W, "highlight").getLength(),
        "Only the genuinely incorrect heading is highlighted");
    var heading = (Element) annotatedBody.getElementsByTagNameNS(W, "p").item(0);
    var headingRuns = heading.getElementsByTagNameNS(W, "r");
    for (int index = 0; index < headingRuns.getLength(); index++) {
      var run = (Element) headingRuns.item(index);
      if (run.getElementsByTagNameNS(W, "t").getLength() == 0) continue;
      assertEquals("宋体", ((Element) run.getElementsByTagNameNS(W, "rFonts").item(0))
          .getAttributeNS(W, "eastAsia"));
      assertEquals("24", ((Element) run.getElementsByTagNameNS(W, "sz").item(0))
          .getAttributeNS(W, "val"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"FONT", "SIZE"})
  void onlyDescribesTheBlankPropertyThatActuallyFails(String property) throws Exception {
    Path source = fixture("paragraphMark", "第六章  系统测试", "黑体", 32,
        property.equals("FONT") ? "宋体" : "黑体", property.equals("SIZE") ? 24 : 32);
    var findings = blankTypographyFindings(source);
    assertEquals(1, findings.size(), findings.toString());
    assertEquals("HEAD-02-BLANK-" + property, findings.getFirst().ruleId());
    Path output = temp.resolve("one-property.docx");

    var report = new CommentWriter().writeCopy(source, output, findings);

    assertTrue(report.outputWritten(), report.items().toString());
    String text = read(output, "word/comments.xml").getDocumentElement().getTextContent();
    assertTrue(text.contains("选中整个空白行（包括其中的空格、换行标记和段落标记）"), text);
    assertEquals(property.equals("FONT"), text.contains("当前中文字体为宋体"), text);
    assertEquals(property.equals("SIZE"), text.contains("当前字号为12.0 磅"), text);
    assertEquals(property.equals("FONT"), text.contains("中文字体设为黑体"), text);
    assertEquals(property.equals("SIZE"), text.contains("字号设为三号"), text);
  }

  @Test
  void doesNotReportACompliantHeadingOrItsCompliantBlankLine() throws Exception {
    Path source = fixture("paragraphMark", "第六章  系统测试", "黑体", 32, "黑体", 32);

    var findings = blankTypographyFindings(source);

    assertTrue(findings.isEmpty(), findings.toString());
  }

  @Test
  void keepsEachOriginalBlankLineWhenFindingsShareAPageStart() throws Exception {
    Path source = fixture("twoParagraphMarks", "第六章  系统测试");
    var findings = blankTypographyFindings(source);
    assertEquals(4, findings.size(), findings.toString());
    Path output = temp.resolve("two-blank-lines.docx");

    var report = new CommentWriter().writeCopy(source, output, findings);

    assertTrue(report.outputWritten(), report.items().toString());
    assertEquals(4, report.items().size());
    assertTrue(report.items().stream().allMatch(item -> item.status() == AnnotationStatus.WRITTEN));
    String text = read(output, "word/comments.xml").getDocumentElement().getTextContent();
    assertEquals(2, text.split("原位置：", -1).length - 1, text);
    assertTrue(text.contains("文档第2段"), text);
    assertTrue(text.contains("文档第3段"), text);
    assertSingleZeroWidthComment(output);
    assertTrue(bodyWithoutCommentAnchors(source).isEqualNode(bodyWithoutCommentAnchors(output)));
  }

  @Test
  void explainsHowToSelectABlankLineCreatedByManualBreaks() throws Exception {
    Path source = fixture("manualBreak", "第六章  系统测试");
    var findings = blankTypographyFindings(source);
    assertEquals(2, findings.size(), findings.toString());
    Path output = temp.resolve("manual-break.docx");

    assertWritten(new CommentWriter().writeCopy(source, output, findings));

    String text = read(output, "word/comments.xml").getDocumentElement().getTextContent();
    assertTrue(text.contains("本条检查标题下方的空白行，不是标题文字。"), text);
    assertTrue(text.contains("选中该空白行的换行标记"), text);
    assertFalse(text.contains("选中该空白行的段落标记"), text);
    assertEquals(1, text.split("原位置：", -1).length - 1, text);
    // The writer may highlight the precise break run; title text formatting remains unchanged.
    var document = read(output, "word/document.xml");
    var titleRun = (Element) document.getElementsByTagNameNS(W, "t").item(0).getParentNode();
    assertEquals("黑体", ((Element) titleRun.getElementsByTagNameNS(W, "rFonts").item(0))
        .getAttributeNS(W, "eastAsia"));
    assertEquals("32", ((Element) titleRun.getElementsByTagNameNS(W, "sz").item(0))
        .getAttributeNS(W, "val"));
    assertEquals(0, titleRun.getElementsByTagNameNS(W, "highlight").getLength());
  }

  @ParameterizedTest
  @ValueSource(strings = {"FONT", "SIZE"})
  void unknownBlankFormatDoesNotClaimAKnownMismatch(String property) throws Exception {
    Path source = fixture("paragraphMark", "摘    要");
    var result = new CheckResult("HEAD-02-BLANK-" + property, Applicability.APPLICABLE,
        ExecutionStatus.EXECUTED, Judgement.REVIEW, property.equals("FONT") ? "黑体" : "三号（16 磅）",
        property.equals("FONT") ? "中文=null" : "无法读取空行字号",
        "FIRST_LEVEL_BLANK_LINE_" + property + "_UNKNOWN",
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 4));
    Path target = temp.resolve("unknown.docx");
    assertTrue(new CommentWriter().writeCopy(source, target, List.of(result)).outputWritten());
    String text = read(target, "word/comments.xml").getDocumentElement().getTextContent();
    assertTrue(text.contains("未能读取"), text);
    assertTrue(text.contains("请在 Word 中确认"), text);
    assertFalse(text.contains("null"), text);
    assertFalse(text.contains("当前中文字体为"), text);
  }

  @ParameterizedTest
  @ValueSource(strings = {"paragraphMark", "emptyRun", "whitespaceRun", "standaloneBreak"})
  void writesRealBlankLineFailuresAndRefreshesThemWithoutChangingDocumentContent(String formatSource)
      throws Exception {
    Path source = fixture(formatSource);
    var raw = new DocxReader().read(source);
    var parsed = new Docx4jDocumentParser().parse(raw);
    var findings = blankTypographyFindings(source);
    assertEquals(2, findings.size(), findings.toString());
    assertEquals(RULE_IDS, findings.stream().map(CheckResult::ruleId).collect(java.util.stream.Collectors.toSet()));
    assertTrue(findings.stream().allMatch(item -> item.judgement() == Judgement.FAIL), findings.toString());
    assertTrue(findings.stream().allMatch(item -> item.source().elementPath().get(1) == 1), findings.toString());
    assertEquals(3, parsed.paragraphs().size());
    if (formatSource.equals("paragraphMark")) assertTrue(parsed.paragraphs().get(1).runs().isEmpty());

    Path output = temp.resolve(formatSource + "-annotated.docx");
    var report = new CommentWriter().writeCopy(source, output, raw.sourceSha256(), findings);

    assertWritten(report);
    assertEquals(findings.stream().map(CheckResult::source).toList(),
        report.items().stream().map(AnnotationItem::source).toList());
    assertTrue(bodyWithoutCommentAnchors(source).isEqualNode(bodyWithoutCommentAnchors(output)),
        "Text, paragraph/run formatting, page breaks and section properties must remain unchanged");
    assertSingleZeroWidthComment(output);
    String text = read(output, "word/comments.xml").getDocumentElement().getTextContent();
    assertTrue(text.contains("选中整个空白行（包括其中的空格、换行标记和段落标记）"), text);

    // Re-open, detect the same real FAILs, then refresh existing inspection comments.
    var repeatedFindings = blankTypographyFindings(output);
    assertEquals(2, repeatedFindings.size(), repeatedFindings.toString());
    assertTrue(repeatedFindings.stream().allMatch(item -> item.judgement() == Judgement.FAIL));
    Path repeated = temp.resolve(formatSource + "-rechecked.docx");
    var repeatedReport = new CommentWriter().writeCopy(output, repeated,
        new DocxReader().read(output).sourceSha256(), repeatedFindings);

    assertWritten(repeatedReport);
    assertSingleZeroWidthComment(repeated);
    assertTrue(bodyWithoutCommentAnchors(source).isEqualNode(bodyWithoutCommentAnchors(repeated)),
        "Refreshing comments must preserve original document content and formatting");
    assertEquals(raw.sourceSha256(), new DocxReader().read(source).sourceSha256());
  }

  private List<CheckResult> blankTypographyFindings(Path source) throws Exception {
    var document = new Docx4jDocumentParser().parse(new DocxReader().read(source));
    return new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> RULE_IDS.contains(item.ruleId()) && item.judgement() != Judgement.PASS).toList();
  }

  private Path fixture(String formatSource) throws Exception {
    return fixture(formatSource, "第一章  绪论");
  }

  private Path fixture(String formatSource, String title) throws Exception {
    return fixture(formatSource, title, "黑体", 32, "宋体", 24);
  }

  private Path fixture(String formatSource, String title, String headingFont, int headingSize,
      String blankFont, int blankSize) throws Exception {
    String runProperties = "<w:rPr><w:rFonts w:ascii='Arial' w:eastAsia='" + blankFont + "'/>"
        + "<w:sz w:val='" + blankSize + "'/></w:rPr>";
    String blank = switch (formatSource) {
      case "paragraphMark" -> "<w:p><w:pPr>" + runProperties + "</w:pPr></w:p>";
      case "twoParagraphMarks" -> ("<w:p><w:pPr>" + runProperties + "</w:pPr></w:p>").repeat(2);
      case "manualBreak" -> "";
      case "emptyRun" -> "<w:p><w:r>" + runProperties + "</w:r></w:p>";
      case "whitespaceRun" -> "<w:p><w:r>" + runProperties + "<w:t xml:space='preserve'> </w:t></w:r></w:p>";
      case "standaloneBreak" -> "<w:p><w:r>" + runProperties + "<w:br/></w:r></w:p>";
      default -> throw new IllegalArgumentException(formatSource);
    };
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document xmlns:w='" + W + "'><w:body>"
            + "<w:p><w:pPr><w:pageBreakBefore/><w:keepNext/></w:pPr>"
            + "<w:r><w:rPr><w:rFonts w:ascii='Times New Roman' w:eastAsia='" + headingFont
            + "'/><w:sz w:val='" + headingSize + "'/></w:rPr><w:t>" + title + "</w:t></w:r>"
            + (formatSource.equals("manualBreak") ? "<w:r>" + runProperties + "<w:br/><w:br/></w:r>" : "")
            + "</w:p>" + blank
            + "<w:p><w:pPr><w:spacing w:line='360'/><w:ind w:firstLine='480'/></w:pPr>"
            + "<w:r><w:t>正文内容</w:t></w:r></w:p><w:sectPr>"
            + "<w:pgSz w:w='11906' w:h='16838'/><w:pgMar w:top='1440' w:bottom='1440'/>"
            + "</w:sectPr></w:body></w:document>"));
    Path source = temp.resolve(formatSource + ".docx");
    pkg.save(source.toFile());
    return source;
  }

  private void assertWritten(AnnotationReport report) {
    assertTrue(report.outputWritten(), report.items().toString());
    assertEquals(2, report.items().size(), report.items().toString());
    assertTrue(report.items().stream().allMatch(item -> item.status() == AnnotationStatus.WRITTEN),
        report.items().toString());
  }

  private void assertSingleZeroWidthComment(Path source) throws Exception {
    var document = read(source, "word/document.xml");
    assertEquals(1, document.getElementsByTagNameNS(W, "commentRangeStart").getLength());
    assertEquals(1, document.getElementsByTagNameNS(W, "commentRangeEnd").getLength());
    assertEquals(1, document.getElementsByTagNameNS(W, "commentReference").getLength());
    Node start = document.getElementsByTagNameNS(W, "commentRangeStart").item(0);
    assertEquals("commentRangeEnd", start.getNextSibling().getLocalName());
    assertEquals(0, document.getElementsByTagNameNS(W, "highlight").getLength());
    var comments = read(source, "word/comments.xml");
    assertEquals(1, comments.getElementsByTagNameNS(W, "comment").getLength());
  }

  private Element bodyWithoutCommentAnchors(Path source) throws Exception {
    var document = read(source, "word/document.xml");
    for (String name : List.of("commentRangeStart", "commentRangeEnd", "commentReference")) {
      var matches = document.getElementsByTagNameNS(W, name);
      var remove = new ArrayList<Node>();
      for (int i = 0; i < matches.getLength(); i++) {
        Node node = matches.item(i);
        remove.add(name.equals("commentReference") ? node.getParentNode() : node);
      }
      for (Node node : remove) node.getParentNode().removeChild(node);
    }
    return (Element) document.getElementsByTagNameNS(W, "body").item(0);
  }

  private org.w3c.dom.Document read(Path source, String part) throws Exception {
    try (var zip = new ZipFile(source.toFile())) {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      return factory.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry(part)));
    }
  }

  private String visibleText(Element root) {
    StringBuilder text = new StringBuilder();
    var nodes = root.getElementsByTagNameNS(W, "t");
    for (int index = 0; index < nodes.getLength(); index++) text.append(nodes.item(index).getTextContent());
    return text.toString();
  }
}
