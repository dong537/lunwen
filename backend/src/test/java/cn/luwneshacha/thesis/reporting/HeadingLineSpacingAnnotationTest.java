package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.rules.Judgement;
import cn.luwneshacha.thesis.rules.RuleEngine;
import cn.luwneshacha.thesis.rules.RuleSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Document;
import org.docx4j.wml.Styles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HeadingLineSpacingAnnotationTest {
  private static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";
  @TempDir Path temp;

  @ParameterizedTest
  @CsvSource({"288, auto, FAIL", "240, auto, FAIL", "360, exact, FAIL", "360, auto, PASS"})
  void checksEffectiveThirdLevelStyleAndWritesOnlyFailures(int line, String rule, Judgement expected)
      throws Exception {
    Path source = fixture(line, rule, "");
    var raw = new DocxReader().read(source);
    var parsed = new Docx4jDocumentParser().parse(raw);
    assertEquals(line, parsed.paragraphs().get(0).lineTwips());
    assertEquals(rule, parsed.paragraphs().get(0).lineRule());
    var findings = new RuleEngine().check(parsed, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("HEADING-LINE")).toList();
    assertEquals(2, findings.size(), "每个三级标题均应进入行距检查");
    assertTrue(findings.stream().allMatch(item -> item.judgement() == expected));
    if (expected == Judgement.PASS) return;

    Path target = temp.resolve("annotated.docx");
    var report = new CommentWriter().writeCopy(source, target, raw.sourceSha256(), findings);
    assertEquals(2, report.items().size());
    assertTrue(report.items().stream().allMatch(item -> item.status() == AnnotationStatus.WRITTEN));
    try (var zip = new ZipFile(target.toFile())) {
      String comments = xml(zip, "word/comments.xml");
      assertEquals(2, occurrences(comments, "1.5 倍"));
      assertFalse(comments.contains("twips"));
      assertFalse(comments.contains("XML"));
      if (line == 288) assertEquals(2, occurrences(comments, "1.2 倍"));
      String document = xml(zip, "word/document.xml");
      assertEquals(2, occurrences(document, "<w:commentRangeStart"));
      assertEquals(2, occurrences(document, "<w:commentRangeEnd"));
      assertEquals(2, occurrences(document, "<w:commentReference"));
    }
    // Re-open through the same public parser: annotations must not alter formatting or text.
    var reread = new Docx4jDocumentParser().parse(new DocxReader().read(target));
    assertEquals(parsed.paragraphs().stream().map(p -> p.runs().stream()
        .map(r -> r.text()).reduce("", String::concat)).toList(),
        reread.paragraphs().stream().map(p -> p.runs().stream()
            .map(r -> r.text()).reduce("", String::concat)).toList());
    assertEquals(List.of(line, line), reread.paragraphs().stream().map(p -> p.lineTwips()).toList());
  }

  @Test
  void directOneAndAHalfSpacingOverridesTheIncorrectInheritedStyle() throws Exception {
    Path source = fixture(288, "auto", "<w:spacing w:line=\"360\" w:lineRule=\"auto\"/>");
    var parsed = new Docx4jDocumentParser().parse(new DocxReader().read(source));
    var findings = new RuleEngine().check(parsed, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("HEADING-LINE")).toList();
    assertEquals(2, findings.size());
    assertTrue(findings.stream().allMatch(item -> item.judgement() == Judgement.PASS));
  }

  private Path fixture(int line, String rule, String directSpacing) throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    StringBuilder paragraphs = new StringBuilder();
    for (String text : List.of("3.1.1 技术可行性", "3.1.2 经济可行性")) {
      paragraphs.append("<w:p><w:pPr><w:pStyle w:val=\"CustomThird\"/>")
          .append(directSpacing).append("</w:pPr><w:r><w:t>")
          .append(text).append("</w:t></w:r></w:p>");
    }
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>" + paragraphs + "<w:sectPr/></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:styleId=\"Heading3\">"
            + "<w:name w:val=\"heading 3\"/><w:pPr><w:outlineLvl w:val=\"2\"/>"
            + "<w:spacing w:line=\"360\" w:lineRule=\"auto\"/></w:pPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"CustomThird\"><w:basedOn w:val=\"Heading3\"/>"
            + "<w:pPr><w:spacing w:line=\"" + line + "\" w:lineRule=\"" + rule
            + "\"/></w:pPr></w:style></w:styles>"));
    Path source = temp.resolve("source.docx");
    pkg.save(source.toFile());
    return source;
  }

  private static String xml(ZipFile zip, String name) throws Exception {
    return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
  }

  private static int occurrences(String text, String target) {
    return text.split(java.util.regex.Pattern.quote(target), -1).length - 1;
  }
}
