package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.document.ParsedDocument;
import java.nio.file.Path;
import java.util.List;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OptionalAppendixPolicyTest {
  @TempDir Path directory;

  @Test
  void completeThesisWithoutAppendixDoesNotRequireOneInBodyOrContents() throws Exception {
    var document = parse("", "");
    assertTrue(new RequiredSectionRuleChecker().check(document).isEmpty());
    var findings = check(document);
    assertNoAppendixFindings(findings);
  }

  @Test
  void proseMentionDoesNotActivateAppendixChecks() throws Exception {
    var findings = check(parse("", paragraph("相关材料可见附录，本文在此说明研究范围。")));
    assertNoAppendixFindings(findings);
  }

  @ParameterizedTest
  @ValueSource(strings = {"附    录", "附录A 名词术语及缩略词"})
  void contentsEntryWithoutActualAppendixDoesNotActivateAppendixFormatting(String entry) throws Exception {
    var findings = check(parse(contents(entry), ""));
    assertNoAppendixFindings(findings);
    assertTrue(findings.stream().anyMatch(result ->
        result.reasonCode().equals("TOC_ENTRY_NOT_FOUND_IN_BODY")), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("TOC-ENTRY-PAGE")
        && result.judgement() == Judgement.FAIL), findings.toString());
  }

  @Test
  void fictitiousAppendixWithPageNumberStillFailsContentsCorrespondence() throws Exception {
    var findings = check(parse(contents("附    录……9"), ""));
    assertNoAppendixFindings(findings);
    assertTrue(findings.stream().anyMatch(result ->
        result.reasonCode().equals("TOC_ENTRY_NOT_FOUND_IN_BODY")), findings.toString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"附    录", "附录A 名词术语及缩略词"})
  void manualContentsWithoutStyleOrPageIsNotAnActualAppendix(String entry) throws Exception {
    var findings = check(parse(manualContents(entry), ""));
    assertNoAppendixFindings(findings);
    assertTrue(findings.stream().anyMatch(result -> result.reasonCode().equals("TOC_ENTRY_NOT_FOUND_IN_BODY")),
        findings.toString());
  }

  @Test
  void actualAppendixWithWrongContentsLikeFormattingStillGetsCheckedAfterBody() throws Exception {
    var document = parse(manualContents("附    录"),
        manualContents("附录") + manualContents("附录B 源程序"));
    var findings = check(document);
    var actualTitle = document.paragraphs().get(document.paragraphs().size() - 2).source();
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("APP-02-SPACING")
        && result.judgement() == Judgement.FAIL
        && result.source().elementPath().equals(actualTitle.elementPath())), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("APP-01")
        && result.judgement() == Judgement.FAIL), findings.toString());
    var entry = document.paragraphs().get(21).source();
    assertFalse(findings.stream().anyMatch(result -> result.ruleId().startsWith("APP-")
        && result.source().elementPath().equals(entry.elementPath())), findings.toString());
  }

  @Test
  void actualAppendixStillRequiresContentsEntryAndChecksItsFormat() throws Exception {
    var findings = check(parse("", paragraph("附录") + paragraph("附录B 源程序")));
    for (String rule : List.of("APP-02-SPACING", "APP-02-FONT", "APP-02-SIZE", "APP-02-BLANK", "APP-01")) {
      assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals(rule)
          && result.judgement() == Judgement.FAIL), rule + ": " + findings);
    }
    assertTrue(findings.stream().anyMatch(result ->
        result.reasonCode().equals("TOC_REQUIRED_APPENDIX_MISSING")), findings.toString());
  }

  @Test
  void letteredAppendixWithoutMainTitleStillRequiresThatTitleAndContentsEntry() throws Exception {
    var findings = check(parse("", paragraph("附录A 源程序")));
    assertTrue(findings.stream().anyMatch(result -> result.reasonCode().equals("APPENDIX_TITLE_MISSING")),
        findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.reasonCode().equals("TOC_REQUIRED_APPENDIX_MISSING")),
        findings.toString());
  }

  @Test
  void actualAppendixContentsEntryStillRequiresPageNumber() throws Exception {
    var findings = check(parse(contents("附    录"),
        paragraph("附    录") + "<w:p/>" + paragraph("附录A 源程序")));
    assertTrue(findings.stream().anyMatch(result ->
        result.reasonCode().equals("TOC_REQUIRED_ENTRY_PAGE_MISSING")
            && result.expected().startsWith("附录")), findings.toString());
  }

  private static void assertNoAppendixFindings(List<CheckResult> findings) {
    assertFalse(findings.stream().anyMatch(result -> result.ruleId().startsWith("APP-")
        || result.reasonCode().startsWith("APPENDIX_")
        || result.reasonCode().equals("TOC_REQUIRED_APPENDIX_MISSING")
        || result.reasonCode().equals("REQUIRED_SECTION_MISSING") && result.expected().equals("附录")),
        findings.toString());
  }

  private static List<CheckResult> check(ParsedDocument document) {
    return new RuleEngine().check(document, RuleSet.schoolV1());
  }

  private ParsedDocument parse(String additionalContents, String appendix) throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    String coverAndAbstracts = List.of("山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文",
        "学生姓名：张三", "学号：20260001", "院系：信息工程学院", "专业年级：计算机科学与技术",
        "指导教师：李四", "2026年6月", "摘    要", "摘要内容。", "关键词：格式；检查；论文",
        "ABSTRACT", "English abstract.", "Keywords: Format; Check; Thesis").stream()
        .map(OptionalAppendixPolicyTest::paragraph).collect(java.util.stream.Collectors.joining());
    String contents = paragraph("目    录") + "<w:p/>" + contents("第一章 引言……1")
        + contents("第五章 结论……5") + contents("参考文献……6") + contents("致    谢……7")
        + additionalContents;
    String body = paragraph("第一章  引言") + "<w:p/>" + paragraph("本文讨论论文格式检查的结构和方法。")
        + paragraph("第五章  结论") + "<w:p/>" + paragraph("研究结果。")
        + paragraph("参考文献") + "<w:p/>" + paragraph("[1] 作者.题名[M].北京：出版社，2026：1-2.")
        + paragraph("致    谢") + "<w:p/>" + paragraph("感谢指导教师。") + appendix;
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + coverAndAbstracts + contents + body + "</w:body></w:document>"));
    Path source = directory.resolve("optional-appendix.docx");
    pkg.save(source.toFile());
    return new Docx4jDocumentParser().parse(new DocxReader().read(source));
  }

  private static String paragraph(String text) {
    return "<w:p><w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r></w:p>";
  }

  private static String contents(String text) {
    return "<w:p><w:pPr><w:pStyle w:val=\"TOC1\"/></w:pPr><w:r><w:t xml:space=\"preserve\">"
        + text + "</w:t></w:r></w:p>";
  }

  private static String manualContents(String text) {
    return "<w:p><w:r><w:rPr><w:sz w:val=\"24\"/><w:b w:val=\"false\"/>"
        + "</w:rPr><w:t xml:space=\"preserve\">" + text + "</w:t></w:r></w:p>";
  }
}
