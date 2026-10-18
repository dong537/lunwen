package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class AbstractRuleCheckerTest {
  @Test
  void reportsReviewWhenChineseKeywordAlignmentIsUnknown() {
    var title = paragraph("摘    要", 0, "黑体", 32, true, "center");
    var blank = paragraph("", 1, "宋体", 24, false, "left");
    var keywords = paragraph("关键词：智能；系统；推荐", 2, "宋体", 24, true, null);
    var document = new ParsedDocument("source", List.of(title, blank, keywords), List.of(), List.of());

    var finding = new AbstractRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.reasonCode().equals("KEYWORD_ALIGNMENT_UNKNOWN"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, finding.judgement());
  }

  @Test
  void ignoresLeadingWhitespaceBeforeChineseKeywordLabelWhenCheckingBold() {
    var title = paragraph("摘    要", 0, "黑体", 32, true, "center");
    var blank = paragraph("", 1, "宋体", 24, false, "left");
    var source = new SourceAddress("word/document.xml", List.of(0, 2), 0, 13);
    var keywords = new ParagraphModel(List.of(
        run(" ", 0, false), run("关键词", 1, true), run("：智能；系统；推荐", 2, false)),
        200, null, null, null, 360, "auto", source, 200, null, "both");
    var document = new ParsedDocument("source", List.of(title, blank, keywords), List.of(), List.of());

    var finding = new AbstractRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("ABSTRACT-KEYWORD-BOLD"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.PASS, finding.judgement());
  }

  @Test
  void acceptsBoldEnglishKeywordLabelAcrossRunsWithRegularSuffix() {
    var findings = inspectEnglishKeywordBold(
        run("Key", 0, true), run("words", 1, true), run(":", 2, true), run(" alpha; beta", 3, false));

    assertEquals(Judgement.PASS, englishKeywordBoldFinding(findings).judgement());
  }

  @Test
  void acceptsBoldEnglishKeywordLabelIncludingFullWidthColon() {
    var findings = inspectEnglishKeywordBold(
        run("Keywords", 0, true), run("：", 1, true), run(" alpha; beta", 2, false));

    assertEquals(Judgement.PASS, englishKeywordBoldFinding(findings).judgement());
  }

  @Test
  void rejectsBoldEnglishKeywordContentInTheSameRunAsTheLabel() {
    var findings = inspectEnglishKeywordBold(run("Keywords: alpha; beta", 0, true));

    var finding = englishKeywordBoldFinding(findings);
    assertEquals(Judgement.FAIL, finding.judgement());
    assertEquals("KEYWORD_CONTENT_BOLD", finding.reasonCode());
    assertEquals("关键词内容被加粗", finding.actual());
    assertEquals(new SourceAddress("word/document.xml", List.of(0, 2), 10, 21), finding.source());
  }

  @Test
  void acceptsBoldColonAndIgnoresBoldWhitespaceAfterTheEnglishKeywordLabel() {
    var findings = inspectEnglishKeywordBold(
        run("Keywords", 0, true), run(": ", 1, true), run("alpha; beta", 2, false));

    assertEquals(Judgement.PASS, englishKeywordBoldFinding(findings).judgement());
  }

  @Test
  void rejectsAnUnboldColonWhenTheEnglishKeywordLabelSpansRuns() {
    var findings = inspectEnglishKeywordBold(
        run("Keywords", 0, true), run(":", 1, false), run(" ", 2, false), run("alpha; beta", 3, false));

    var finding = englishKeywordBoldFinding(findings);
    assertEquals(Judgement.FAIL, finding.judgement());
    assertEquals("KEYWORD_LABEL_NOT_BOLD", finding.reasonCode());
  }

  @Test
  void identifiesOnlyKeywordContentAsBoldAfterTheEnglishKeywordLabel() {
    var findings = inspectEnglishKeywordBold(
        run("Keywords", 0, true), run(":", 1, true), run(" ", 2, false), run("alpha; beta", 3, true));

    var finding = englishKeywordBoldFinding(findings);
    assertEquals(Judgement.FAIL, finding.judgement());
    assertEquals("关键词内容被加粗", finding.actual());
  }

  @Test
  void ignoresLeadingWhitespaceBeforeEnglishKeywordLabelWhenCheckingBold() {
    var findings = inspectEnglishKeywordBold(
        run(" ", 0, false), run("Keywords", 1, true), run(":", 2, true), run(" alpha; beta", 3, false));

    assertEquals(Judgement.PASS, englishKeywordBoldFinding(findings).judgement());
  }

  @Test
  void rejectsAnUnboldCharacterWhenTheEnglishKeywordLabelSpansRuns() {
    var findings = inspectEnglishKeywordBold(
        run("Key", 0, true), run("words", 1, false), run(": alpha; beta", 2, false));

    var finding = englishKeywordBoldFinding(findings);
    assertEquals(Judgement.FAIL, finding.judgement());
    assertEquals("KEYWORD_LABEL_NOT_BOLD", finding.reasonCode());
  }

  @Test
  void reportsReviewWhenWrappedEnglishKeywordsHaveNoAlignmentEvidence() {
    var findings = inspectEnglishKeywordBold(
        run("Keywords: alpha;\n", 0, true), run("beta; gamma", 1, false));

    var finding = findings.stream().filter(result -> result.ruleId().equals("EN-03-ALIGNMENT"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, finding.judgement());
    assertEquals("ENGLISH_KEYWORD_WRAP_ALIGNMENT_UNKNOWN", finding.reasonCode());
  }

  @Test
  void acceptsWrappedEnglishKeywordsWhenHangingIndentEvidenceExists() {
    var source = new SourceAddress("word/document.xml", List.of(0, 2), 0, 30);
    var keywordParagraph = new ParagraphModel(List.of(
        run("Keywords: alpha;\n", 0, true), run("beta; gamma", 1, false)),
        null, 480, null, null, 360, "auto", source, null, null, "left", null, null, null,
        false, 240, 2, false, false, false, false, null, null);
    var document = new ParsedDocument("source", List.of(
        paragraph("ABSTRACT", 0, "Times New Roman", 32, true, "center"),
        paragraph("", 1, "Times New Roman", 24, false, "left"), keywordParagraph),
        List.of(), List.of());

    var finding = new AbstractRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("EN-03-ALIGNMENT"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.PASS, finding.judgement());
  }

  @Test
  void stopsAbstractFormattingAtAnUnnumberedIntroductionWhenKeywordsAreMissing() {
    var title = paragraph("ABSTRACT", 0, "Times New Roman", 32, true, "center");
    var blank = paragraph("", 1, "Times New Roman", 24, false, "left");
    var introduction = paragraph("绪论", 2, "黑体", 32, true, "center");
    var body = paragraph("正文内容", 3, "Arial", 20, false, "left");
    var document = new ParsedDocument("source", List.of(title, blank, introduction, body),
        List.of(), List.of());

    var findings = new AbstractRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1());

    assertEquals(0, findings.stream().filter(result -> result.source().equals(body.source())
        && (result.ruleId().startsWith("ABSTRACT-") || result.ruleId().startsWith("EN-"))).count());
  }

  @Test
  void doesNotStartAnotherAbstractBlockAfterAutomaticNumberedBodyBegins() {
    var title = paragraph("ABSTRACT", 0, "Times New Roman", 32, true, "center");
    var blank = paragraph("", 1, "Times New Roman", 24, false, "left");
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 4);
    var numberedChapter = new ParagraphModel(List.of(run("绪论", 2, true)), 0, null, null, null,
        360, "auto", chapterSource, 0, 0, "center", "1", 1, 0, false, null, null, true,
        false, false, false, null, null, "Heading1");
    var repeatedAbstract = paragraph("ABSTRACT", 3, "Arial", 20, false, "left");
    var body = paragraph("正文内容", 4, "Arial", 20, false, "left");
    var document = new ParsedDocument("source", List.of(title, blank, numberedChapter,
        repeatedAbstract, body), List.of(), List.of());

    var findings = new AbstractRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1());

    assertEquals(0, findings.stream().filter(result -> result.source().equals(body.source())
        && (result.ruleId().startsWith("ABSTRACT-") || result.ruleId().startsWith("EN-"))).count());
  }

  @Test
  void stopsAbstractFormattingAtAnUnnumberedHeadingOneStyle() {
    var title = paragraph("ABSTRACT", 0, "Times New Roman", 32, true, "center");
    var blank = paragraph("", 1, "Times New Roman", 24, false, "left");
    var headingSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 4);
    var heading = new ParagraphModel(List.of(new RunModel("研究背景", "黑体", "黑体", null,
        32, true, headingSource)), 0, null, null, null, 360, "auto", headingSource,
        0, 0, "center", null, null, null, false, null, null, false, false, false,
        false, null, null, "Heading1");
    var body = paragraph("正文内容", 3, "宋体", 24, false, "left");
    var document = new ParsedDocument("source", List.of(title, blank, heading, body),
        List.of(), List.of());

    var findings = new AbstractRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1());

    assertEquals(0, findings.stream().filter(result ->
        result.source().equals(headingSource) || result.source().equals(body.source())).count());
  }

  @Test
  void tableChapterTextDoesNotEndAbstractFormatting() {
    var title = paragraph("ABSTRACT", 0, "Times New Roman", 32, true, "center");
    var blank = paragraph("", 1, "Times New Roman", 24, false, "left");
    var tableSource = new SourceAddress("word/document.xml", List.of(0, 2, 0, 0), 0, 6);
    var tableChapter = new ParagraphModel(List.of(run("第一章 绪论", 2, false)), null, null,
        null, null, 360, "auto", tableSource, null, 0, "left", "1", null, null, false,
        null, null, true);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        null, null, 1, 1, false, false, false, false, false, false);
    var abstractText = paragraph("English abstract text", 3, "Times New Roman", 24, false, "left");
    var document = new ParsedDocument("source", List.of(title, blank, tableChapter, abstractText),
        List.of(), List.of(), List.of(table), List.of(), List.of(), List.of());

    var findings = new AbstractRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("ABSTRACT-FONT")
        && result.source().equals(abstractText.runs().get(0).source())), findings.toString());
  }

  @Test
  void ignoresTextBoxPromptsAndPseudoChaptersBeforeTheEnglishAbstractBody() {
    var title = paragraph("ABSTRACT", 0, "Times New Roman", 32, true, "center");
    var prompt = textBoxParagraph("中文摘要一般不超过300字。", 1, 30);
    var pseudoChapter = textBoxParagraph("第一章 绪论", 2, 30);
    var blank = paragraph("", 3, "Times New Roman", 24, false, "left");
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 4), 0, 44);
    var bodyRun = new RunModel("This is the genuine English abstract text.", "Times New Roman",
        "Times New Roman", null, 24, false, bodySource);
    var abstractBody = new ParagraphModel(List.of(bodyRun), 480, null, null, null,
        288, "auto", bodySource, 200, null, "left");
    var document = new ParsedDocument("source", List.of(title, prompt, pseudoChapter, blank, abstractBody),
        List.of(), List.of());

    var findings = new AbstractRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().noneMatch(result -> result.source().equals(prompt.source())
        || result.source().equals(pseudoChapter.source())), findings.toString());
    assertTrue(findings.stream().noneMatch(result ->
        "ABSTRACT_TITLE_BLANK_LINE_MISSING".equals(result.reasonCode())), findings.toString());
    var bodyLineSpacing = findings.stream()
        .filter(result -> result.ruleId().equals("ABSTRACT-LINE") && result.source().equals(bodySource))
        .findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, bodyLineSpacing.judgement());
    assertEquals("LINE_SPACING_MISMATCH", bodyLineSpacing.reasonCode());
  }

  private static CheckResult englishKeywordBoldFinding(List<CheckResult> findings) {
    return findings.stream().filter(result -> result.ruleId().equals("EN-03-BOLD"))
        .findFirst().orElseThrow();
  }

  private static List<CheckResult> inspectEnglishKeywordBold(RunModel... runs) {
    var title = paragraph("ABSTRACT", 0, "Times New Roman", 32, true, "center");
    var blank = paragraph("", 1, "Times New Roman", 24, false, "left");
    int textLength = java.util.Arrays.stream(runs).mapToInt(run -> run.text().length()).sum();
    var source = new SourceAddress("word/document.xml", List.of(0, 2), 0, textLength);
    var keywordParagraph = new ParagraphModel(List.of(runs), 200, null, null, null,
        360, "auto", source, 200, null, "left");
    var document = new ParsedDocument("source", List.of(title, blank, keywordParagraph), List.of(), List.of());

    return new AbstractRuleChecker(new ParagraphFormatChecker()).check(document, RuleSet.schoolV1());
  }

  private static RunModel run(String text, int runIndex, Boolean bold) {
    var source = new SourceAddress("word/document.xml", List.of(0, 2, runIndex), 0, text.length());
    return new RunModel(text, "Times New Roman", "", null, 24, bold, source);
  }

  private static ParagraphModel paragraph(String text, int index, String eastAsiaFont,
      int size, Boolean bold, String alignment) {
    var source = new SourceAddress("word/document.xml", List.of(0, index), 0, text.length());
    var run = new RunModel(text, "Times New Roman", eastAsiaFont, null, size, bold, source);
    return new ParagraphModel(List.of(run), 200, null, null, null, 360, "auto", source,
        200, null, alignment);
  }

  private static ParagraphModel textBoxParagraph(String text, int index, int size) {
    var source = new SourceAddress("word/document.xml", List.of(0, index), 0, text.length());
    var run = new RunModel(text, "Times New Roman", "宋体", null, size, false, source);
    return new ParagraphModel(List.of(run), null, null, null, null, null, null, source,
        null, null, "left", null, null, null, false, null, null, false,
        false, false, false, null, null, null, false, true);
  }
}
