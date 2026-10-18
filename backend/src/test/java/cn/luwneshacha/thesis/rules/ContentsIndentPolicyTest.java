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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContentsIndentPolicyTest {
  @TempDir Path directory;
  private Path source;

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
  void lowerLevelsUseWholeParagraphLeftIndentWithoutRequiringFirstLineAttributes() throws Exception {
    var parsed = parse(entry("TOC2", "<w:ind w:leftChars=\"200\"/>", "2.1 方法……2")
        + entry("TOC3", "<w:ind w:leftChars=\"400\"/>", "2.1.1 实现……3"));
    var findings = indentFindings(parsed);

    assertEquals(2, findings.size());
    assertTrue(findings.stream().allMatch(r -> r.judgement() == Judgement.PASS), findings.toString());
    assertTrue(findings.stream().noneMatch(r -> r.expected().contains("firstLine")), findings.toString());
  }

  @Test
  void firstLineIndentDoesNotSubstituteForWholeParagraphLeftIndent() throws Exception {
    var parsed = parse(entry("TOC2", "<w:ind w:left=\"0\" w:firstLineChars=\"200\"/>", "2.1 方法……2"));
    var finding = indentFindings(parsed).getFirst();

    assertEquals(Judgement.FAIL, finding.judgement());
    assertTrue(finding.expected().contains("leftChars=200"), finding.toString());
  }

  @Test
  void actualWordCommentExplainsWholeParagraphLeftIndent() throws Exception {
    var parsed = parse(entry("TOC2", "<w:ind w:left=\"0\"/>", "2.1 方法……2"));
    var finding = indentFindings(parsed).getFirst();
    assertEquals(Judgement.FAIL, finding.judgement());
    Path annotated = directory.resolve("annotated.docx");
    var annotations = new CommentWriter().writeCopy(source, annotated, parsed.sourceSha256(), List.of(finding));
    assertTrue(annotations.outputWritten());
    try (ZipFile zip = new ZipFile(annotated.toFile());
        var stream = zip.getInputStream(zip.getEntry("word/comments.xml"))) {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      String comment = factory.newDocumentBuilder().parse(stream).getDocumentElement().getTextContent();
      assertTrue(comment.contains("左侧缩进") && comment.contains("2 字符"), comment);
      assertFalse(comment.contains("将首行缩进") || comment.contains("首行缩进为 2 字符"), comment);
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

  private ParsedDocument parse(String entries) throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + "<w:p><w:r><w:t xml:space=\"preserve\">目    录</w:t></w:r></w:p><w:p/>"
            + entries + "</w:body></w:document>"));
    source = directory.resolve("contents.docx");
    pkg.save(source.toFile());
    return new Docx4jDocumentParser().parse(new DocxReader().read(source));
  }

  private static String entry(String style, String indent, String title) {
    return "<w:p><w:pPr><w:pStyle w:val=\"" + style + "\"/>" + indent
        + "</w:pPr><w:r><w:t xml:space=\"preserve\">" + title + "</w:t></w:r></w:p>";
  }
}
