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

class ConclusionChapterPolicyTest {
  @TempDir Path directory;

  @ParameterizedTest
  @ValueSource(strings = {"第五章  结论", "第七章  结论", "第四章  结论与展望"})
  void numberedConclusionKeepsItsOwnChapterNumber(String title) throws Exception {
    var document = parse(contents(title), heading(title, "黑体", 32) + "<w:p/>");

    assertTrue(new RequiredSectionRuleChecker().check(document).isEmpty());
    var findings = new RuleEngine().check(document, RuleSet.schoolV1());
    assertFalse(findings.stream().anyMatch(result ->
        result.reasonCode().startsWith("CONCLUSION_CHAPTER_")), findings.toString());
    assertFalse(findings.stream().anyMatch(result ->
        result.reasonCode().equals("TOC_ENTRY_NOT_FOUND_IN_BODY")
            || result.reasonCode().equals("BODY_HEADING_NOT_IN_TOC")), findings.toString());
    var conclusion = document.paragraphs().stream()
        .filter(paragraph -> paragraph.runs().stream().map(run -> run.text())
            .reduce("", String::concat).equals(title))
        .findFirst().orElseThrow();
    for (String rule : List.of("HEADING-FONT", "HEADING-SIZE")) {
      assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals(rule)
          && result.source().elementPath().equals(conclusion.source().elementPath())
          && result.judgement() == Judgement.PASS), rule + ": " + findings);
    }
  }

  @Test
  void chapterSevenStillChecksHeadingFormatBlankLineAndContentsCorrespondence() throws Exception {
    var document = parse(contents("第五章  结论"), heading("第七章  结论", "宋体", 24));
    var findings = new RuleEngine().check(document, RuleSet.schoolV1());
    var conclusion = document.paragraphs().stream()
        .filter(paragraph -> paragraph.runs().stream().anyMatch(run -> run.text().equals("第七章  结论")))
        .findFirst().orElseThrow();

    assertFalse(findings.stream().anyMatch(result ->
        result.reasonCode().startsWith("CONCLUSION_CHAPTER_")), findings.toString());
    for (String rule : List.of("HEADING-FONT", "HEADING-SIZE")) {
      assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals(rule)
          && result.source().elementPath().equals(conclusion.source().elementPath())
          && result.judgement() == Judgement.FAIL), rule + ": " + findings);
    }
    var conclusionBody = document.paragraphs().stream()
        .filter(paragraph -> paragraph.runs().stream().anyMatch(run -> run.text().equals("研究结果。")))
        .findFirst().orElseThrow();
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("HEAD-02-BLANK")
        && result.source().elementPath().equals(conclusionBody.source().elementPath())
        && result.judgement() == Judgement.FAIL), findings.toString());
    assertTrue(findings.stream().anyMatch(result ->
        result.reasonCode().equals("BODY_HEADING_NOT_IN_TOC")
            && result.source().elementPath().equals(conclusion.source().elementPath())), findings.toString());
    assertTrue(findings.stream().anyMatch(result ->
        result.reasonCode().equals("TOC_ENTRY_NOT_FOUND_IN_BODY")), findings.toString());
  }

  @Test
  void contentsConclusionDoesNotReplaceAMissingBodyConclusion() throws Exception {
    var findings = new RuleEngine().check(parse(contents("第七章  结论"), ""), RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(result -> result.expected().equals("结论")
        && result.reasonCode().equals("REQUIRED_SECTION_MISSING")), findings.toString());
  }

  private ParsedDocument parse(String conclusionContents, String conclusion) throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    String frontMatter = List.of("山东石油化工学院", "本科生毕业设计（论文）", "题目：毕业论文",
        "学生姓名：张三", "学号：20260001", "院系：信息工程学院", "专业年级：计算机科学与技术",
        "指导教师：李四", "2026年6月", "摘    要", "摘要内容。", "关键词：格式；检查；论文",
        "ABSTRACT", "English abstract.", "Keywords: Format; Check; Thesis").stream()
        .map(ConclusionChapterPolicyTest::paragraph).collect(java.util.stream.Collectors.joining());
    String toc = paragraph("目    录") + "<w:p/>" + contents("第一章  绪论")
        + conclusionContents + contents("参考文献") + contents("致    谢");
    String body = heading("第一章  绪论", "黑体", 32) + "<w:p/>" + paragraph("本文讨论论文格式。")
        + conclusion + paragraph("研究结果。") + paragraph("参考文献") + "<w:p/>"
        + paragraph("[1] 作者.题名[M].北京：出版社，2026：1-2.")
        + paragraph("致    谢") + "<w:p/>" + paragraph("感谢指导教师。");
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
            + frontMatter + toc + body + "</w:body></w:document>"));
    Path source = directory.resolve("conclusion-policy.docx");
    pkg.save(source.toFile());
    return new Docx4jDocumentParser().parse(new DocxReader().read(source));
  }

  private static String paragraph(String text) {
    return "<w:p><w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r></w:p>";
  }

  private static String contents(String text) {
    return "<w:p><w:pPr><w:pStyle w:val=\"TOC1\"/></w:pPr><w:r><w:t xml:space=\"preserve\">"
        + text + "……1</w:t></w:r></w:p>";
  }

  private static String heading(String text, String font, int size) {
    return "<w:p><w:pPr><w:outlineLvl w:val=\"0\"/><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr>"
        + "<w:rFonts w:ascii=\"Times New Roman\" w:eastAsia=\"" + font + "\"/>"
        + "<w:sz w:val=\"" + size + "\"/></w:rPr><w:t xml:space=\"preserve\">"
        + text + "</w:t></w:r></w:p>";
  }
}
