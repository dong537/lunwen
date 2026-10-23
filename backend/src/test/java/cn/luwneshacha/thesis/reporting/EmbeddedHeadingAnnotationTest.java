package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.inspection.RuleCatalog;
import cn.luwneshacha.thesis.report.RuleDescriptions;
import cn.luwneshacha.thesis.rules.CheckResult;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EmbeddedHeadingAnnotationTest {
  @TempDir Path temp;
  private static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";

  @ParameterizedTest
  @ValueSource(strings = {"6.3 测试用例", "6.3.1 边界测试"})
  void diagnosesJoinedHeadingWithoutApplyingBodyFontOrSizeToIt(String title) throws Exception {
    String prefix = "  前述测试完成[1]。";
    int size = title.startsWith("6.3.1") ? 24 : 28;
    String titleXml = run(title.substring(0, 2), "黑体", size)
        + "<w:bookmarkStart w:id=\"1\" w:name=\"title\"/>"
        + run(title.substring(2), "黑体", size) + "<w:bookmarkEnd w:id=\"1\"/>";
    Path input = fixture(title, paragraph(run(prefix, "宋体", 24) + titleXml), true);
    var raw = new DocxReader().read(input);
    var parsed = new Docx4jDocumentParser().parse(raw);
    var target = parsed.paragraphs().get(2);
    var results = new RuleEngine().check(parsed, RuleSet.schoolV1());
    var structural = results.stream().filter(r -> r.ruleId().equals("HEAD-EMBEDDED")).toList();
    assertEquals(1, structural.size(), "标题与正文同段时应提示拆段");
    var result = structural.getFirst();
    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals(prefix.length(), result.source().start());
    assertEquals(prefix.length() + title.length(), result.source().end());
    assertEquals(target.source().elementPath(), result.source().elementPath());
    assertEquals(title, result.actual());
    assertNotNull(RuleCatalog.find(result.ruleId()));
    assertEquals("标题独立成段", RuleDescriptions.name(result));
    assertTrue(results.stream().filter(r -> at(r, target) && bodyTextRule(r))
        .allMatch(r -> r.source().end() <= prefix.length()), results.toString());
    assertTrue(results.stream().anyMatch(r -> at(r, target) && r.ruleId().equals("BODY-LINE")));
    assertTrue(results.stream().anyMatch(r -> at(r, target) && r.ruleId().equals("BODY-INDENT")));
    assertTrue(results.stream().anyMatch(r -> at(r, target) && r.ruleId().equals("BODY-FONT")
        && r.judgement() == Judgement.PASS));
    assertTrue(results.stream().filter(r -> at(r, target) && bodyTextRule(r))
        .noneMatch(r -> r.source().start() < prefix.indexOf(']') + 1
            && r.source().end() > prefix.indexOf('[')), "引用区间仍须从正文检查中排除");

    Path output = temp.resolve("annotated.docx");
    var report = new CommentWriter().writeCopy(input, output, raw.sourceSha256(), structural);
    assertEquals(1, report.items().size());
    assertEquals(AnnotationStatus.WRITTEN, report.items().getFirst().status());
    try (var zip = new ZipFile(output.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml"))
          .readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(comments.contains("Enter"));
      assertTrue(comments.contains("独立成段"));
      assertTrue(comments.contains(title));
      assertFalse(comments.contains("改为宋体"));
      assertFalse(comments.contains("XML"));
    }
    var reread = new Docx4jDocumentParser().parse(new DocxReader().read(output));
    assertEquals(parsed.paragraphs().stream().map(EmbeddedHeadingAnnotationTest::text).toList(),
        reread.paragraphs().stream().map(EmbeddedHeadingAnnotationTest::text).toList());
  }

  @Test
  void retainsErrorsInBodyTextEvenWhenTheHeadingStartsInsideTheSameRun() throws Exception {
    String title = "6.3 测试用例";
    String prefix = "正常正文。";
    String badBody = "这段正文误用了标题格式。";
    Path input = fixture(title, paragraph(run(prefix, "宋体", 24)
        + run(badBody + title, "黑体", 28)), true);
    var parsed = new Docx4jDocumentParser().parse(new DocxReader().read(input));
    var results = new RuleEngine().check(parsed, RuleSet.schoolV1());
    var target = parsed.paragraphs().get(2);
    assertEquals(1, results.stream().filter(r -> r.ruleId().equals("HEAD-EMBEDDED")).count());
    for (String id : List.of("BODY-FONT", "BODY-SIZE")) {
      assertTrue(results.stream().anyMatch(r -> at(r, target) && r.ruleId().equals(id)
          && r.judgement() == Judgement.FAIL && r.source().start() == prefix.length()
          && r.source().end() == prefix.length() + badBody.length()), results.toString());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"数值为6.3 测试用例", "请参阅6.3 测试用例", "说明。6.3 测试用例介绍如下。",
      "版本。6.3.1 Release", "日期。2026.10 更新说明", "值。6.3 kg"})
  void doesNotPromoteNumbersOrProseToHeadings(String prose) throws Exception {
    Path input = fixture("6.3 测试用例", paragraph(run(prose, "黑体", 28)), true);
    var results = new RuleEngine().check(new Docx4jDocumentParser().parse(new DocxReader().read(input)),
        RuleSet.schoolV1());
    assertTrue(results.stream().noneMatch(r -> r.ruleId().equals("HEAD-EMBEDDED")), results.toString());
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("BODY-FONT")
        && r.judgement() == Judgement.FAIL), "真实正文格式问题不能被隐藏");
  }

  @Test
  void requiresBothContentsEvidenceAndHeadingFormatting() throws Exception {
    String title = "6.3 测试用例";
    for (boolean contents : List.of(false, true)) {
      Path input = fixture(title, paragraph(run("内容结束。", "宋体", 24)
          + run(title, contents ? "宋体" : "黑体", contents ? 24 : 28)), contents);
      var results = new RuleEngine().check(new Docx4jDocumentParser().parse(new DocxReader().read(input)),
          RuleSet.schoolV1());
      assertTrue(results.stream().noneMatch(r -> r.ruleId().equals("HEAD-EMBEDDED")), results.toString());
    }
  }

  @Test
  void leavesSeparateHeadingAndItsNormalFormatChecksIntact() throws Exception {
    String title = "6.3 测试用例";
    Path input = fixture(title, paragraph(run("内容结束。", "宋体", 24))
        + paragraph(run(title, "黑体", 28)), true);
    var parsed = new Docx4jDocumentParser().parse(new DocxReader().read(input));
    var results = new RuleEngine().check(parsed, RuleSet.schoolV1());
    var heading = parsed.paragraphs().get(3);
    assertTrue(results.stream().noneMatch(r -> r.ruleId().equals("HEAD-EMBEDDED")));
    assertTrue(results.stream().anyMatch(r -> at(r, heading) && r.ruleId().equals("HEADING-SIZE")
        && r.judgement() == Judgement.PASS));
    assertTrue(results.stream().noneMatch(r -> at(r, heading) && bodyTextRule(r)));
  }

  @Test
  void contentsCorrespondenceRecognizesTheSameEmbeddedHeading() throws Exception {
    String title = "6.3 测试用例";
    Path input = fixture(title, paragraph(run("内容结束。", "宋体", 24)
        + run(title, "黑体", 28)), true, paragraph(run("目    录", "黑体", 32)));
    var parsed = new Docx4jDocumentParser().parse(new DocxReader().read(input));
    var results = new RuleEngine().check(parsed, RuleSet.schoolV1());
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("HEAD-EMBEDDED")));
    assertTrue(results.stream().noneMatch(r -> r.reasonCode().equals("TOC_ENTRY_NOT_FOUND_IN_BODY")
        && at(r, parsed.paragraphs().get(1))), results.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"6.3  测试用例", "6.3\u3000测试用例", "6.3测试用例"})
  void checksTheTitleSeparatorAtItsOriginalOffset(String title) throws Exception {
    String prefix = "前面的正文没有格式问题。";
    Path input = fixture("6.3 测试用例", paragraph(run(prefix, "宋体", 24)
        + run(title, "黑体", 28)), true);
    var parsed = new Docx4jDocumentParser().parse(new DocxReader().read(input));
    var target = parsed.paragraphs().get(2);
    var results = new RuleEngine().check(parsed, RuleSet.schoolV1());
    var separator = results.stream().filter(r -> at(r, target)
        && r.ruleId().equals("HEAD-02-SPACING")).toList();
    assertEquals(1, separator.size());
    assertEquals(Judgement.FAIL, separator.getFirst().judgement());
    assertTrue(separator.getFirst().source().start() >= prefix.length() + 3);
    assertTrue(separator.getFirst().source().end() <= prefix.length() + title.length());
    assertTrue(results.stream().filter(r -> at(r, target) && bodyTextRule(r))
        .allMatch(r -> r.source().end() <= prefix.length()));
    Path output = temp.resolve("separator.docx");
    var annotation = new CommentWriter().writeCopy(input, output,
        parsed.sourceSha256(), AnnotationFindings.select(results).representatives());
    assertTrue(annotation.items().stream().anyMatch(i -> i.ruleId().equals("HEAD-02-SPACING")
        && i.status() == AnnotationStatus.WRITTEN));
    try (var zip = new ZipFile(output.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml"))
          .readAllBytes(), StandardCharsets.UTF_8);
      assertFalse(comments.contains("正文：字号调整为小四号"));
      assertTrue(comments.contains("独立成段"));
    }
  }

  private Path fixture(String title, String body, boolean contents) throws Exception {
    return fixture(title, body, contents, "");
  }

  private Path fixture(String title, String body, boolean contents, String before) throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    String toc = contents ? "<w:p><w:pPr><w:pStyle w:val=\"TOC2\"/></w:pPr>"
        + run(title, "宋体", 24) + "<w:r><w:tab/><w:t>38</w:t></w:r></w:p>" : "<w:p/>";
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>" + before + toc
            + paragraph(run("第六章  系统测试", "黑体", 32)) + body + "<w:sectPr/></w:body></w:document>"));
    Path source = temp.resolve("source.docx");
    pkg.save(source.toFile());
    return source;
  }

  private static String paragraph(String runs) {
    return "<w:p><w:pPr><w:spacing w:line=\"360\" w:lineRule=\"auto\"/>"
        + "<w:ind w:firstLineChars=\"200\"/></w:pPr>" + runs + "</w:p>";
  }

  private static String run(String text, String font, int size) {
    return "<w:r><w:rPr><w:rFonts w:eastAsia=\"" + font
        + "\" w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\"/><w:sz w:val=\""
        + size + "\"/></w:rPr><w:t xml:space=\"preserve\">" + text + "</w:t></w:r>";
  }

  private static String text(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(r -> r.text()).reduce("", String::concat);
  }

  private static boolean at(CheckResult result, ParagraphModel paragraph) {
    return result.source() != null && result.source().partName().equals(paragraph.source().partName())
        && result.source().elementPath().equals(paragraph.source().elementPath());
  }

  private static boolean bodyTextRule(CheckResult result) {
    return result.ruleId().equals("BODY-FONT") || result.ruleId().equals("BODY-SIZE");
  }
}
