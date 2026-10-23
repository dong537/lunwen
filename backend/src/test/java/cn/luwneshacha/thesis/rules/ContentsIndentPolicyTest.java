package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.reporting.CommentWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Document;
import org.docx4j.wml.Style;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContentsIndentPolicyTest {
  @TempDir Path directory;
  private Path source;

  @ParameterizedTest
  @ValueSource(strings = {
      "<w:ind w:left=\"0\" w:firstLineChars=\"200\"/>",
      "<w:ind w:firstLine=\"480\"/>",
      "<w:ind w:leftChars=\"200\"/>",
      ""
  })
  void secondLevelEntriesDoNotRequireWholeParagraphLeftIndent(String indent) throws Exception {
    var parsed = parse(entry("TOC2", indent, "2.1 方法……2"));
    assertTrue(indentFindings(parsed).isEmpty(), indentFindings(parsed).toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "<w:ind w:left=\"0\" w:firstLineChars=\"400\"/>",
      "<w:ind w:firstLine=\"960\"/>",
      "<w:ind w:leftChars=\"400\"/>",
      ""
  })
  void thirdLevelEntriesAcceptFirstLineIndentWithoutIntroducingAnotherRequirement(String indent) throws Exception {
    var parsed = parse(entry("TOC3", indent, "2.1.1 实现……3"));
    assertTrue(indentFindings(parsed).isEmpty(), indentFindings(parsed).toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "<w:ind w:left=\"0\" w:firstLine=\"0\"/>"})
  void firstLevelAndEndMatterEntriesAreFlushLeftWithoutFirstLineIndent(String indent) throws Exception {
    var parsed = parse(entry("TOC1", indent, "第一章  引言……1")
        + entry("TOC1", indent, "参考文献……7")
        + entry("TOC1", indent, "致    谢……8")
        + entry("TOC1", indent, "附    录……9"));
    var findings = indentFindings(parsed);

    assertEquals(4, findings.size());
    assertTrue(findings.stream().allMatch(r -> r.judgement() == Judgement.PASS), findings.toString());
    assertTrue(findings.stream().noneMatch(r -> r.expected().contains("firstLine")), findings.toString());
  }

  @Test
  void inheritedFirstLineIndentRemainsDistinctFromLeftIndent() throws Exception {
    var parsed = parse(entry("TOC2", "", "2.1 方法……2")
        + entry("TOC3", "", "2.1.1 实现……3"),
        style("TOC2", "目录 2", 200), style("TOC3", "目录 3", 400));
    assertEquals(200, parsed.paragraphs().get(2).firstLineChars());
    assertEquals(400, parsed.paragraphs().get(3).firstLineChars());
    assertEquals(null, parsed.paragraphs().get(2).leftIndentChars());
    assertEquals(null, parsed.paragraphs().get(3).leftIndentChars());
    var findings = indentFindings(parsed);

    assertTrue(findings.isEmpty(), findings.toString());
  }

  @Test
  void localizedContentsStyleDoesNotFallBackToFirstLevelWhenNumberIsMissing() throws Exception {
    var parsed = parse(entry("CustomTOC", "<w:ind w:leftChars=\"400\"/>", "研究范围……2"),
        style("CustomTOC", "目录 3", 400));
    assertEquals("目录 3", parsed.paragraphs().getLast().paragraphStyleName());
    assertTrue(indentFindings(parsed).isEmpty(), indentFindings(parsed).toString());
  }

  @Test
  void brokenNumberSpacingDoesNotTurnLowerLevelIntoFirstLevel() throws Exception {
    var parsed = parse(entry("", "<w:ind w:leftChars=\"400\"/>", "2 .1.1 实现……2"));
    var findings = new RuleEngine().check(parsed, RuleSet.schoolV1());
    assertFalse(findings.stream().anyMatch(r -> r.ruleId().equals("TOC-ENTRY-INDENT")), findings.toString());
    assertTrue(findings.stream().anyMatch(r -> r.ruleId().equals("TOC-SPACING")
        && r.judgement() == Judgement.FAIL), findings.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"2.1.1 研究范围", "2.1.1 研究范围\tX", "2 .1.1 研究范围", "2 .1.1 研究范围\tX"})
  void manualContentsWithMissingOrInvalidPageIsNotCheckedAsBodyHeading(String title) throws Exception {
    var parsed = parse(entry("", "<w:ind w:firstLine=\"960\"/>", title)
        .replace("<w:r><w:t", "<w:r><w:rPr><w:rFonts w:eastAsia=\"宋体\" w:ascii=\"Times New Roman\"/>"
            + "<w:sz w:val=\"24\"/></w:rPr><w:t"));
    var sourcePath = parsed.paragraphs().getLast().source().elementPath();
    var findings = new RuleEngine().check(parsed, RuleSet.schoolV1()).stream()
        .filter(r -> r.source().elementPath().equals(sourcePath)).toList();
    assertTrue(findings.stream().anyMatch(r -> r.ruleId().equals("TOC-ENTRY-PAGE")
        && r.judgement() == Judgement.FAIL), findings.toString());
    if (title.startsWith("2 ")) {
      assertTrue(findings.stream().anyMatch(r -> r.ruleId().equals("TOC-SPACING")
          && r.judgement() == Judgement.FAIL), findings.toString());
    }
    assertFalse(findings.stream().anyMatch(r -> r.ruleId().startsWith("HEAD")
        || r.ruleId().equals("BODY-INDENT") || r.ruleId().equals("TOC-ENTRY-INDENT")), findings.toString());
  }

  @Test
  void lowerLevelCompatibilityStillWritesOtherFormatErrorsToWord() throws Exception {
    var parsed = parse(entry("TOC3", "<w:ind w:firstLineChars=\"400\"/>"
        + "<w:spacing w:line=\"240\" w:lineRule=\"auto\"/>", "2.1.1实现")
        .replace("<w:r><w:t", "<w:r><w:rPr><w:rFonts w:eastAsia=\"黑体\" w:ascii=\"Arial\"/>"
            + "<w:sz w:val=\"28\"/></w:rPr><w:t"));
    var entryPath = parsed.paragraphs().getLast().source().elementPath();
    var findings = new RuleEngine().check(parsed, RuleSet.schoolV1()).stream()
        .filter(r -> r.source().elementPath().equals(entryPath) && r.judgement() == Judgement.FAIL).toList();
    for (String ruleId : List.of("TOC-ENTRY-FONT", "TOC-ENTRY-SIZE", "TOC-ENTRY-LINE", "TOC-SPACING", "TOC-ENTRY-PAGE")) {
      assertTrue(findings.stream().anyMatch(r -> r.ruleId().equals(ruleId)), ruleId + ": " + findings);
    }
    assertFalse(findings.stream().anyMatch(r -> r.ruleId().contains("INDENT")), findings.toString());
    Path annotated = directory.resolve("annotated.docx");
    var annotations = new CommentWriter().writeCopy(source, annotated, parsed.sourceSha256(), findings);
    assertTrue(annotations.outputWritten());
    try (ZipFile zip = new ZipFile(annotated.toFile());
        var stream = zip.getInputStream(zip.getEntry("word/comments.xml"))) {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      String comment = factory.newDocumentBuilder().parse(stream).getDocumentElement().getTextContent();
      assertTrue(comment.contains("宋体") && comment.contains("1.5 倍") && comment.contains("页码"), comment);
      assertFalse(comment.contains("缩进"), comment);
      assertFalse(comment.contains("twips") || comment.contains("leftChars"), comment);
    }
  }

  @Test
  void ordinaryBodyStillRequiresFirstLineIndent() throws Exception {
    var parsed = parse(entry("TOC1", "", "第一章  引言……1")
        + "<w:p><w:pPr><w:outlineLvl w:val=\"0\"/></w:pPr><w:r><w:t>第一章  引言</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>这一段是没有首行缩进的真实正文内容。</w:t></w:r></w:p>");
    var bodySource = parsed.paragraphs().getLast().source();
    var findings = new RuleEngine().check(parsed, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(r -> r.ruleId().equals("BODY-INDENT")
        && r.source().elementPath().equals(bodySource.elementPath()) && r.judgement() == Judgement.FAIL),
        findings.toString());
    var entrySource = parsed.paragraphs().get(2).source();
    assertFalse(findings.stream().anyMatch(r -> r.ruleId().equals("BODY-INDENT")
        && r.source().elementPath().equals(entrySource.elementPath())), findings.toString());
  }

  private List<CheckResult> indentFindings(ParsedDocument document) {
    return new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("TOC-ENTRY-INDENT")).toList();
  }

  private ParsedDocument parse(String entries, String... styles) throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    for (String style : styles) {
      pkg.getMainDocumentPart().getStyleDefinitionsPart().getContents().getStyle()
          .add((Style) XmlUtils.unmarshalString(style));
    }
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + "<w:p><w:r><w:t xml:space=\"preserve\">目    录</w:t></w:r></w:p><w:p/>"
            + entries + "</w:body></w:document>"));
    source = directory.resolve("contents.docx");
    pkg.save(source.toFile());
    return new Docx4jDocumentParser().parse(new DocxReader().read(source));
  }

  private static String entry(String style, String indent, String title) {
    return "<w:p><w:pPr>" + (style.isEmpty() ? "" : "<w:pStyle w:val=\"" + style + "\"/>") + indent
        + "</w:pPr><w:r><w:t xml:space=\"preserve\">" + title + "</w:t></w:r></w:p>";
  }

  @Test
  void lowerLevelBodyHeadingAfterChapterStillRequiresTwoCharacterFirstLineIndent() throws Exception {
    var parsed = parse(entry("TOC3", "<w:ind w:firstLineChars=\"400\"/>", "1.1.1 研究背景……1")
        + "<w:p><w:pPr><w:outlineLvl w:val=\"0\"/></w:pPr><w:r><w:t>第一章  引言</w:t></w:r></w:p>"
        + "<w:p/>" + entry("Heading3", "<w:ind w:firstLineChars=\"400\"/>", "1.1.1 研究背景")
            .replace("<w:r><w:t", "<w:r><w:rPr><w:sz w:val=\"24\"/><w:b w:val=\"false\"/></w:rPr><w:t"));
    var headingPath = parsed.paragraphs().getLast().source().elementPath();
    var findings = new RuleEngine().check(parsed, RuleSet.schoolV1());
    assertTrue(findings.stream().anyMatch(r -> r.ruleId().equals("HEAD-03-INDENT")
        && r.source().elementPath().equals(headingPath) && r.judgement() == Judgement.FAIL), findings.toString());
  }

  private static String style(String id, String name, int firstLineChars) {
    return "<w:style xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
        + " w:type=\"paragraph\" w:styleId=\"" + id + "\"><w:name w:val=\"" + name + "\"/>"
        + "<w:pPr><w:ind w:firstLineChars=\"" + firstLineChars + "\"/>"
        + "<w:spacing w:line=\"360\" w:lineRule=\"auto\"/></w:pPr></w:style>";
  }
}
