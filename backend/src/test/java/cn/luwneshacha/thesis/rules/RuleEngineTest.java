package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SectionModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import cn.luwneshacha.thesis.document.ReferenceEntryModel;
import cn.luwneshacha.thesis.document.ImageModel;
import cn.luwneshacha.thesis.document.PageNumberModel;
import cn.luwneshacha.thesis.document.HeadingBreakModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleEngineTest {
  private static final SourceAddress ADDRESS = new SourceAddress("word/document.xml", List.of(0), 0, 2);

  @Test
  void reportsPassForSchoolV1Formatting() {
    var run = new RunModel("正文A", "Times New Roman", "宋体", null, 24, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, null, null, ADDRESS, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph("", List.of(0, 1)), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().allMatch(result -> result.judgement() == Judgement.PASS));
  }

  @Test
  void reportsFailForWrongMarginAndFontSize() {
    var run = new RunModel("正文A", "Arial", "宋体", null, 22, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, null, null, ADDRESS, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1418, 1474, 1474, 1474, ADDRESS)), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertEquals(Judgement.FAIL, results.stream().filter(r -> r.ruleId().equals("PAGE-01")).findFirst().orElseThrow().judgement());
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("BODY-FONT") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("BODY-SIZE") && r.judgement() == Judgement.FAIL));
  }

  @Test
  void reportsReviewWhenRequiredIndentEvidenceIsMissing() {
    var run = new RunModel("正文", "Times New Roman", "宋体", null, 24, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), null, null, null, null, null, ADDRESS, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    var result = results.stream().filter(r -> r.ruleId().equals("BODY-INDENT")).findFirst().orElseThrow();
    assertEquals(ExecutionStatus.EXECUTED, result.executionStatus());
    assertEquals(Judgement.REVIEW, result.judgement());
  }

  @Test
  void doesNotApplyBodyRulesToNumberedHeadingWithoutRequiredSpace() {
    var source = new SourceAddress("word/document.xml", List.of(0, 10), 0, 7);
    var heading = new ParagraphModel(List.of(
        new RunModel("3.6本章小结", "Times New Roman", "黑体", null, 28, true, source)),
        0, null, null, null, null, source, 0);
    var document = new ParsedDocument("source", List.of(chapterHeading(), heading), List.of(), List.of());

    var bodyResults = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().startsWith("BODY-")).toList();

    assertTrue(bodyResults.isEmpty());
  }

  @Test
  void doesNotApplyBodyRulesToContinuationTableCaption() {
    var source = new SourceAddress("word/document.xml", List.of(0, 10), 0, 5);
    var continuationCaption = new ParagraphModel(List.of(
        new RunModel("续表4-7", "Times New Roman", "宋体", null, 21, false, source)),
        0, null, null, null, null, source, 0);
    var document = new ParsedDocument("source", List.of(chapterHeading(), continuationCaption), List.of(), List.of());

    var bodyResults = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().startsWith("BODY-")).toList();

    assertTrue(bodyResults.isEmpty());
  }

  @Test
  void ignoresWhitespaceOnlyRunsWhenCheckingHeadingSize() {
    var source = new SourceAddress("word/document.xml", List.of(0, 10), 0, 8);
    var heading = new ParagraphModel(List.of(
        new RunModel("5.1", "Times New Roman", "黑体", null, 28, true, source),
        new RunModel(" ", "Times New Roman", "黑体", null, 32, false, source),
        new RunModel("总体流程", "Times New Roman", "黑体", null, 28, true, source)),
        0, null, null, null, null, source, 0);
    var document = new ParsedDocument("source", List.of(chapterHeading(), heading), List.of(), List.of());

    var sizeFailures = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("HEADING-SIZE") && result.judgement() == Judgement.FAIL).toList();

    assertTrue(sizeFailures.isEmpty());
  }

  @Test
  void reviewsThreeLineBordersOnAContinuationTable() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 11), 0, 0),
        ADDRESS, "续表4-7", 2, 2, false, true, false, false, true, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-02")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("TABLE_CONTINUATION_BORDER_REVIEW", result.reasonCode());
  }

  @Test
  void reviewsUnknownPageNumberFontInsteadOfCallingItMismatch() {
    var page = new PageNumberModel(new SourceAddress("word/footer1.xml", List.of(), 0, 0), true,
        true, null, 21);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(page));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("PAGE-05")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("PAGE_NUMBER_FONT_UNKNOWN", result.reasonCode());
  }

  @Test
  void reviewsUnknownPageNumberAlignmentInsteadOfCallingItFailure() {
    var page = new PageNumberModel(new SourceAddress("word/footer1.xml", List.of(), 0, 0), true,
        null, "Times New Roman", 21);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(page));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("PAGE-05")
            && item.reasonCode().equals("PAGE_NUMBER_ALIGNMENT_UNKNOWN")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
  }

  @Test
  void acceptsTwoCharacterIndentStoredAsTwips() {
    var run = new RunModel("正文", "Times New Roman", "宋体", null, 24, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, null, null, ADDRESS, null);
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("BODY-INDENT")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void doesNotApplyBodyRulesToReferenceEntriesAndHeadings() {
    var run = new RunModel("[1] Example[M].", "Arial", "黑体", null, 18, false, ADDRESS);
    var heading = new ParagraphModel(List.of(run), 0, null, null, null, null, ADDRESS, 0);
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> result.ruleId().startsWith("BODY-")));
  }

  @Test
  void doesNotApplyBodyFormattingRulesInsideTableCells() {
    var tableSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 0);
    var cellSource = new SourceAddress("word/document.xml", List.of(0, 1, 0, 0, 0), 0, 4);
    var run = new RunModel("表格内容", "Arial", "黑体", null, 18, false, cellSource);
    var cellParagraph = new ParagraphModel(List.of(run), 0, null, null, null, null, cellSource, 0);
    var table = new TableModel(tableSource, null, null, 1, 1, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(cellParagraph), List.of(), List.of(), List.of(table),
        List.of(), List.of(), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().startsWith("BODY-")).toList();

    assertTrue(results.isEmpty());
  }

  @Test
  void appliesBodyRulesOnlyBetweenFirstChapterAndEndMatter() {
    var cover = paragraph("论文题目", List.of(0, 0));
    var abstractTitle = paragraph("摘  要", List.of(0, 1));
    var tocEntry = paragraph("第1章 绪论 ........ 1", List.of(0, 2));
    var firstChapter = paragraph("第1章 绪论", List.of(0, 3));
    var body = paragraph("正文内容", List.of(0, 4));
    var references = paragraph("REFERENCES", List.of(0, 5));
    var referenceText = paragraph("文献条目内容", List.of(0, 6));
    var acknowledgement = paragraph("致谢", List.of(0, 7));
    var afterBody = paragraph("感谢指导老师", List.of(0, 8));
    var document = new ParsedDocument("source", List.of(cover, abstractTitle, tocEntry, firstChapter, body,
        references, referenceText, acknowledgement, afterBody), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().startsWith("BODY-") && result.judgement() != Judgement.PASS).toList();

    assertEquals(3, results.size());
    assertTrue(results.stream().allMatch(result -> result.source().elementPath().equals(List.of(0, 4))));
  }

  private static ParagraphModel paragraph(String text, List<Integer> path) {
    var source = new SourceAddress("word/document.xml", path, 0, text.length());
    var run = new RunModel(text, "Arial", "黑体", null, 18, false, source);
    return new ParagraphModel(List.of(run), 0, null, null, null, null, source, 0);
  }

  private static ParagraphModel chapterHeading() {
    var source = new SourceAddress("word/document.xml", List.of(0, 0), 0, 7);
    var run = new RunModel("第1章  绪论", "Times New Roman", "黑体", null, 32, true, source);
    return new ParagraphModel(List.of(run), 0, null, null, null, null, source, 0);
  }

  @Test
  void treatsAuxiliaryTableLinesAsReviewInsteadOfFailure() {
    var table = new TableModel(ADDRESS, ADDRESS, "表1-1 数据", 3, 2,
        true, true, true, true, true, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("TAB-02")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("TABLE_AUXILIARY_BORDER_REVIEW", result.reasonCode());
  }

  @Test
  void checksReferenceFontSizeAndOnePointFiveLineSpacing() {
    var reference = new ParagraphModel(
        List.of(new RunModel("[1] Paper[M].", "Arial", "黑体", null, 22, false, ADDRESS)),
        null, null, null, 480, "auto", ADDRESS, null);
    var entry = new ReferenceEntryModel(ADDRESS, "1", "[1] Paper[M].", "M", false);
    var document = new ParsedDocument("source", List.of(reference), List.of(), List.of(),
        List.of(), List.of(), List.of(entry), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("REF-FONT") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("REF-SIZE") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("REF-LINE") && r.judgement() == Judgement.FAIL));
  }

  @Test
  void checksCaptionFontAndSizeUsingCaptionParagraphAnchor() {
    var caption = new ParagraphModel(
        List.of(new RunModel("表1-1 数据", "Arial", "黑体", null, 22, false, ADDRESS)),
        null, null, null, null, null, ADDRESS, null);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "表1-1 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("CAPTION-FONT") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("CAPTION-SIZE") && r.judgement() == Judgement.FAIL));
  }

  @Test
  void checksOnePointFiveLineSpacingInsideAbstractZone() {
    var heading = new ParagraphModel(List.of(new RunModel("摘要", "Times New Roman", "宋体", null, 24, false, ADDRESS)),
        0, null, null, 360, "auto", ADDRESS, 0);
    var abstractBody = new ParagraphModel(List.of(new RunModel("这是摘要内容。", "Times New Roman", "宋体", null, 24, false, ADDRESS)),
        480, null, null, 480, "auto", new SourceAddress("word/document.xml", List.of(1), 0, 7), 200);
    var keywords = new ParagraphModel(List.of(new RunModel("关键词：格式", "Times New Roman", "宋体", null, 24, false, ADDRESS)),
        0, null, null, 360, "auto", new SourceAddress("word/document.xml", List.of(2), 0, 7), 0);
    var document = new ParsedDocument("source", List.of(heading, abstractBody, keywords), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    var lines = results.stream().filter(r -> r.ruleId().equals("ABSTRACT-LINE")).toList();
    assertEquals(2, lines.size());
    var line = lines.get(0);
    assertEquals(Judgement.FAIL, line.judgement());
    assertEquals(Judgement.PASS, lines.get(1).judgement());
  }

  @Test
  void rejectsFixedPointLineSpacingThatUsesTheSameTwipsAsOnePointFiveLines() {
    var title = paragraph("摘要", List.of(0, 0));
    var body = new ParagraphModel(List.of(new RunModel("摘要正文", "Times New Roman", "宋体", null, 24, false,
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 4))),
        0, null, null, 360, "exact", new SourceAddress("word/document.xml", List.of(0, 1), 0, 4), 0);
    var document = new ParsedDocument("source", List.of(title, body), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("ABSTRACT-LINE"))
        .findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void acceptsChineseKeywordLineWithBoldLabelAndFourTerms() {
    var title = paragraph("摘要", List.of(0, 0));
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 23);
    var keywords = new ParagraphModel(List.of(
        new RunModel("关键词：", "Times New Roman", "宋体", null, 24, true, source),
        new RunModel("甲；乙；丙；丁", "Times New Roman", "宋体", null, 24, false, source)),
        0, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(title, keywords), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("ABSTRACT-KEYWORD-BOLD") && item.judgement() == Judgement.PASS));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("ABSTRACT-KEYWORD-FORMAT") && item.judgement() == Judgement.PASS));
  }

  @Test
  void rejectsUnboldChineseKeywordLabelAndInvalidKeywordList() {
    var title = paragraph("摘要", List.of(0, 0));
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 18);
    var keywords = new ParagraphModel(List.of(
        new RunModel("关键词:", "Times New Roman", "宋体", null, 24, false, source),
        new RunModel("甲；乙。", "Times New Roman", "宋体", null, 24, false, source)),
        0, null, null, 360, "auto", source, 200);
    var document = new ParsedDocument("source", List.of(title, keywords), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("ABSTRACT-KEYWORD-BOLD") && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("ABSTRACT-KEYWORD-FORMAT") && item.judgement() == Judgement.FAIL));
  }

  @Test
  void checksChineseAbstractFormattingAndStopsAfterKeywords() {
    var title = paragraph("摘  要", List.of(0, 0));
    var body = new ParagraphModel(List.of(new RunModel("摘要正文", "Arial", "黑体", null, 18, false,
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 4))),
        0, null, null, 480, "auto", new SourceAddress("word/document.xml", List.of(0, 1), 0, 4), 0);
    var keywords = new ParagraphModel(List.of(new RunModel("关键词：格式", "Arial", "黑体", null, 18, false,
        new SourceAddress("word/document.xml", List.of(0, 2), 0, 7))),
        0, null, null, 480, "auto", new SourceAddress("word/document.xml", List.of(0, 2), 0, 7), 0);
    var afterKeywords = paragraph("后续说明", List.of(0, 3));
    var document = new ParsedDocument("source", List.of(title, body, keywords, afterKeywords), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertEquals(2, results.stream().filter(result -> result.ruleId().equals("ABSTRACT-FONT")).count());
    assertEquals(2, results.stream().filter(result -> result.ruleId().equals("ABSTRACT-SIZE")).count());
    assertEquals(2, results.stream().filter(result -> result.ruleId().equals("ABSTRACT-INDENT")).count());
    assertEquals(2, results.stream().filter(result -> result.ruleId().equals("ABSTRACT-LINE")).count());
  }

  @Test
  void checksNamedSectionTitleFontAndSize() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 4);
    var title = new ParagraphModel(List.of(new RunModel("参考文献", "Arial", "宋体", null,
        24, false, source)), 0, null, null, null, null, source, 4);
    var document = new ParsedDocument("source", List.of(chapterHeading(), title), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("REF-01-FONT")
        && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("REF-01-SIZE")
        && item.judgement() == Judgement.FAIL));
  }

  @Test
  void checksEnglishAbstractTitleFontAndBold() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 8);
    var title = new ParagraphModel(List.of(new RunModel("ABSTRACT", "Arial", "宋体", null,
        32, false, source)), 0, null, null, null, null, source, 8);
    var document = new ParsedDocument("source", List.of(chapterHeading(), title), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("EN-01-FONT")
        && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("EN-01-BOLD")
        && item.judgement() == Judgement.FAIL));
  }

  @Test
  void reportsNamedSectionTitleAlignmentMismatch() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 4);
    var title = new ParagraphModel(List.of(new RunModel("参考文献", "Times New Roman", "黑体", null,
        32, false, source)), 0, null, null, null, null, source, 4, null, "left");
    var document = new ParsedDocument("source", List.of(chapterHeading(), title), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("REF-01-ALIGNMENT")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
  }

  @Test
  void checksCoverInstitutionHeadingWithoutUsingTemplateTextAsExpectedValue() {
    var source = new SourceAddress("word/document.xml", List.of(0, 0), 0, 12);
    var title = new ParagraphModel(List.of(new RunModel("本科生毕业设计（论文）", "Arial", "黑体", null,
        32, false, source)), 0, null, null, null, null, source, 12, null, "left");
    var document = new ParsedDocument("source", List.of(title, paragraph("摘  要", List.of(0, 1))), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-01-FONT")
        && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-01-SIZE")
        && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-01-ALIGNMENT")
        && item.judgement() == Judgement.FAIL));
  }

  @Test
  void checksCoverFieldFormattingWithoutExpectingSpecificNamesOrIds() {
    var source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 10);
    var field = new ParagraphModel(List.of(
        new RunModel("学生姓名：", "Times New Roman", "仿宋", null, 32, true, source),
        new RunModel("学生甲", "Times New Roman", "仿宋", null, 32, false, source)),
        0, null, null, null, null, source, 10, null, "left");
    var document = new ParsedDocument("source", List.of(paragraph("本科生毕业设计（论文）", List.of(0, 0)), field,
        paragraph("摘  要", List.of(0, 2))), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("COVER-03-BOLD") && item.judgement() == Judgement.FAIL)
        .findFirst().orElseThrow();

    assertEquals(source, result.source());
  }

  @Test
  void checksCoverDateFormattingWithoutAssumingTheSubmissionDate() {
    var source = new SourceAddress("word/document.xml", List.of(0, 2), 0, 10);
    var dateSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 10);
    var date = new ParagraphModel(List.of(new RunModel("2026年6月", "Times New Roman", "宋体", null,
        24, false, dateSource)), 0, null, null, null, null, dateSource, 0, null, "center");
    var document = new ParsedDocument("source", List.of(paragraph("本科生毕业设计（论文）", List.of(0, 0)),
        paragraph("论文题目：", List.of(0, 1)), paragraph("基于样例的研究", List.of(0, 2)), date,
        paragraph("摘要", List.of(0, 4))), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-04-FONT")
        && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-04-SIZE")
        && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-04-BOLD")
        && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().noneMatch(item -> item.ruleId().startsWith("COVER-02-")
        && item.source().elementPath().equals(List.of(0, 1))));
  }

  @Test
  void checksCoverFieldContinuationParagraphs() {
    var firstSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 10);
    var continuationSource = new SourceAddress("word/document.xml", List.of(0, 4), 0, 10);
    var first = new ParagraphModel(List.of(
        new RunModel("院系：信息科学", "Times New Roman", "仿宋", null, 32, true, firstSource)),
        0, null, null, null, null, firstSource, 0, null, "left");
    var continuation = new ParagraphModel(List.of(
        new RunModel("与工程学院", "Arial", "宋体", null, 24, false, continuationSource)),
        0, null, null, null, null, continuationSource, 0, null, "left");
    var document = new ParsedDocument("source", List.of(
        paragraph("本科生毕业设计（论文）", List.of(0, 0)),
        paragraph("论文题目：", List.of(0, 1)),
        paragraph("基于样例的研究", List.of(0, 2)), first, continuation,
        paragraph("摘要", List.of(0, 5))), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-03-FONT")
        && item.judgement() == Judgement.FAIL && item.source().equals(continuationSource)));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-03-SIZE")
        && item.judgement() == Judgement.FAIL && item.source().equals(continuationSource)));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("COVER-03-BOLD")
        && item.judgement() == Judgement.FAIL && item.source().equals(continuationSource)));
  }

  @Test
  void checksAppendixSequenceAndObjectLetterAgainstAppendixScope() {
    var headingA = paragraph("附录 A 源代码", List.of(0, 10));
    var headingC = paragraph("附录 C 补充材料", List.of(0, 20));
    var caption = paragraph("图B1 系统结构", List.of(0, 12));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 11), 0, 0),
        caption.source(), "图B1 系统结构", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), headingA,
        paragraph("图片对象", List.of(0, 11)), caption, headingC),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(image));

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("APP-01")).toList();

    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("APPENDIX_SEQUENCE_MISMATCH")
        && item.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(item -> item.reasonCode().equals("APPENDIX_OBJECT_LETTER_MISMATCH")
        && item.judgement() == Judgement.FAIL && item.source().equals(caption.source())));
    assertTrue(results.stream().anyMatch(item -> item.expected().equals("A") && item.actual().equals("A")
        && item.judgement() == Judgement.PASS));
  }

  @Test
  void recognizesEnglishAppendixHeadingsForLetterSequenceChecks() {
    var document = new ParsedDocument("source", List.of(chapterHeading(),
        paragraph("Appendix A Supplementary material", List.of(0, 10)),
        paragraph("Appendix C Additional material", List.of(0, 20))), List.of(), List.of());

    var appendixChecks = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("APP-01")
            && item.reasonCode().equals("APPENDIX_SEQUENCE_MISMATCH")).toList();

    assertEquals(1, appendixChecks.size());
    assertEquals("B", appendixChecks.get(0).expected());
    assertEquals("C", appendixChecks.get(0).actual());
  }

  @Test
  void doesNotCountFigureReferencesAfterEnglishAppendixAsBodyReferences() {
    var caption = paragraph("图1-1 系统结构图", List.of(0, 2));
    var appendix = paragraph("Appendix A Supplementary material", List.of(0, 4));
    var appendixReference = paragraph("参见图1-1", List.of(0, 5));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "图1-1 系统结构图", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), caption, appendix, appendixReference),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FIGURE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void appendixSequenceMustStartAtAAndAdvanceByAppearanceOrder() {
    var document = new ParsedDocument("source", List.of(
        chapterHeading(), paragraph("附录 B 源代码", List.of(0, 10)),
        paragraph("附录 C 附加材料", List.of(0, 20))), List.of(), List.of());

    var mismatches = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("APP-01")
            && item.reasonCode().equals("APPENDIX_SEQUENCE_MISMATCH")).toList();

    assertEquals(2, mismatches.size());
    assertEquals(List.of("A", "B"), mismatches.stream().map(CheckResult::expected).toList());
  }

  @Test
  void ignoresAppendixEntriesThatEndWithPageNumberInContents() {
    var contentsTitle = paragraph("目录", List.of(0, 1));
    var contentsEntry = paragraph("附录 B 源代码 88", List.of(0, 2));
    var actualAppendix = paragraph("附录 A 源代码", List.of(0, 20));
    var document = new ParsedDocument("source", List.of(contentsTitle, contentsEntry,
        chapterHeading(), actualAppendix), List.of(), List.of());

    var appendixChecks = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("APP-01")).toList();

    assertEquals(1, appendixChecks.size());
    assertEquals(Judgement.PASS, appendixChecks.get(0).judgement());
    assertEquals("A", appendixChecks.get(0).actual());
  }

  @Test
  void checksAppendixEquationReferencesWithinTheSameAppendix() {
    var appendix = paragraph("附录 A 公式", List.of(0, 10));
    var equationParagraph = paragraph("(A1)", List.of(0, 11));
    var reference = paragraph("由式(A1)可得结果。", List.of(0, 12));
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        equationParagraph.source(), "OMML", "x", "A1", true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), appendix,
        equationParagraph, reference), List.of(), List.of(), List.of(), List.of(equation),
        List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-04")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void checksSecondLevelHeadingFontAndSizeWithoutInventingBoldRequirement() {
    var heading = new ParagraphModel(
        List.of(new RunModel("1.1 标题", "Arial", "宋体", null, 24, false, ADDRESS)),
        0, null, null, 360, "auto", ADDRESS, 0);
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("HEADING-FONT") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("HEADING-SIZE") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().noneMatch(r -> r.ruleId().equals("HEADING-BOLD")));
  }

  @Test
  void checksThirdLevelHeadingIndentAndLowerHeadingFormatting() {
    var thirdSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 8);
    var lowerSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 10);
    var third = new ParagraphModel(List.of(new RunModel("2.2.1 标题", "Times New Roman", "黑体", null,
        24, false, thirdSource)), 0, null, null, null, null, thirdSource, 0, null, "left");
    var lower = new ParagraphModel(List.of(new RunModel("（1） 标题", "Times New Roman", "宋体", null,
        24, false, lowerSource)), 0, null, null, null, null, lowerSource, 0, null, "left");
    var document = new ParsedDocument("source", List.of(third, lower), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("HEAD-03-INDENT") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("HEAD-05-INDENT") && r.judgement() == Judgement.FAIL));
  }

  @Test
  void usesWordOutlineLevelToClassifyUnnumberedHeading() {
    var source = new SourceAddress("word/document.xml", List.of(0, 12), 0, 5);
    var heading = new ParagraphModel(List.of(
        new RunModel("结论与展望", "Times New Roman", "黑体", null, 32, false, source)),
        0, null, null, null, null, source, 0, 0);
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> result.ruleId().startsWith("BODY-")));
    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("HEADING-SIZE")
        && result.judgement() == Judgement.PASS));
  }

  @Test
  void usesDedicatedFontRuleForAbstractHeadingWithoutGenericHeadingComment() {
    var source = new SourceAddress("word/document.xml", List.of(0, 12), 0, 8);
    var heading = new ParagraphModel(List.of(
        new RunModel("ABSTRACT", "Times New Roman", "Times New Roman", null, 32, true, source)),
        null, null, null, null, null, source, 0, null, "center");
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(result -> result.ruleId().equals("EN-01-FONT")));
    assertTrue(results.stream().noneMatch(result -> result.ruleId().equals("HEADING-FONT")));
  }

  @Test
  void reportsCaptionBelowTableAsPositionError() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "表1-2 表题", 2, 2, true, true, true, false, false, true);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("TAB-03")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_CAPTION_BELOW", result.reasonCode());
  }

  @Test
  void reportsMissingTableUnitsAndRepeatedPlaceholderText() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        ADDRESS, "表1-1 数据", 2, 2, true, true, true, false, true, false,
        false, List.of(1200, 1200), List.of(360, 360), List.of("数量", "结果"), null, null, true);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("TAB-04")
        && r.reasonCode().equals("TABLE_UNITS_MISSING")));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("TAB-06")
        && r.reasonCode().equals("TABLE_REPEATED_PLACEHOLDER")));
  }

  @Test
  void reportsMissingReferenceFieldsAndNumberGap() {
    var first = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 20),
        "1", "[1] 作者. 书名[M]. 出版社，2024.", "M", false,
        "作者", "书名", "出版社", "2024", null);
    var third = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 12),
        "3", "[3] 只有题名[M].", "M", false,
        null, "只有题名", null, null, null);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(first, third));

    var results = new RuleEngine().check(document, RuleSet.schoolV1());
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("REF-06")
        && r.reasonCode().equals("REFERENCE_FIELDS_MISSING")));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("REF-07")
        && r.reasonCode().equals("REFERENCE_NUMBER_SEQUENCE_MISMATCH")));
  }

  @Test
  void requiresFourSpacesInsideChineseAcknowledgementTitle() {
    var heading = paragraph("致谢", List.of(0, 1));
    var body = paragraph("感谢老师的指导。", List.of(0, 2));
    var document = new ParsedDocument("source", List.of(heading, paragraph("", List.of(0, 1, 1)), body),
        List.of(), List.of(), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("END-01-SPACING")).findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("ACKNOWLEDGEMENT_TITLE_SPACING_MISMATCH", result.reasonCode());
  }

  @Test
  void requiresBlankLineAfterReferencesTitle() {
    var heading = paragraph("参考文献", List.of(0, 1));
    var body = paragraph("[1] 文献[M]. 出版社", List.of(0, 2));
    var reference = new ReferenceEntryModel(body.source(), "1", body.runs().get(0).text(), "M", false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), heading, body), List.of(), List.of(),
        List.of(), List.of(), List.of(reference), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("REF-01-BLANK")).findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("SECTION_TITLE_BLANK_LINE_MISSING", result.reasonCode());
  }

  @Test
  void acceptsTableReferenceInBody() {
    var body = paragraph("详细数据见表1-1。", List.of(0, 1));
    var caption = paragraph("表1-1 数据", List.of(0, 2));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "表1-1 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-01")).findFirst().orElseThrow();
    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void ignoresTableNumberInTocWhenCheckingBodyReference() {
    var toc = paragraph("表4-1 数据........................12", List.of(0, 1));
    var caption = paragraph("表4-1 数据", List.of(0, 3));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        caption.source(), "表4-1 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source",
        List.of(paragraph("目录", List.of(0, 0)), toc, chapterHeading(), caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-01")).findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void reportsFigureCaptionWithoutChapterNumberOrSeparatorSpace() {
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "图1 示例", 100, 200, true, true, true);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-03")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FIGURE_CAPTION_NUMBER_FORMAT", result.reasonCode());
  }

  @Test
  void reportsTableCaptionWithoutChapterNumberOrSeparatorSpace() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "表1 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-03")
            && item.reasonCode().equals("TABLE_CAPTION_NUMBER_FORMAT")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_CAPTION_NUMBER_FORMAT", result.reasonCode());
  }

  @Test
  void reportsMissingQuantityAndUnitLabelsUnderTab04() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "表1-1 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-04")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_UNITS_MISSING", result.reasonCode());
  }

  @Test
  void checksEquationNumberFontAndSizeAcrossRuns() {
    var paragraphSource = new SourceAddress("word/document.xml", List.of(0), 0, 8);
    var equationSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 1);
    var paragraph = new ParagraphModel(List.of(
        new RunModel("(3-", "Times New Roman", "宋体", null, 20, false,
            new SourceAddress("word/document.xml", List.of(0, 0), 0, 3)),
        new RunModel("1)", "Arial", "宋体", null, 22, false,
            new SourceAddress("word/document.xml", List.of(0, 1), 0, 2))),
        0, null, null, null, null, paragraphSource, 8);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        equationSource, "OMML", "x", "3-1", true, false);
    var document = new ParsedDocument("source", List.of(paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-02")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("EQUATION_NUMBER_FORMAT_MISMATCH", result.reasonCode());
  }

  @Test
  void passesEquationNumberWithRequiredEffectiveFormatting() {
    var paragraphSource = new SourceAddress("word/document.xml", List.of(0), 0, 5);
    var equationSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 1);
    var paragraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        20, false, new SourceAddress("word/document.xml", List.of(0, 1), 0, 5))),
        0, null, null, null, null, paragraphSource, 5);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        equationSource, "OMML", "x", "3-1", true, false);
    var document = new ParsedDocument("source", List.of(paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-02")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void keepsEquationRuleIdsAlignedWithSchoolMatrix() {
    var chapterSource = new SourceAddress("word/document.xml", List.of(0, 0), 0, 6);
    var referenceSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 12);
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 5);
    var equationSource = new SourceAddress("word/document.xml", List.of(0, 2, 0), 0, 1);
    var chapter = new ParagraphModel(List.of(new RunModel("第1章 绪论", "Times New Roman", "黑体", null,
        32, true, chapterSource)), 0, null, null, null, null, chapterSource, 0);
    var reference = new ParagraphModel(List.of(new RunModel("由式(3-1)可知", "Times New Roman", "宋体", null,
        24, false, referenceSource)), 480, null, null, 360, "auto", referenceSource, 12);
    var equationParagraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        20, false, equationParagraphSource)), 0, null, null, null, null, equationParagraphSource, 5);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        equationSource, "OMML", "x", "3-1", true, false);
    var document = new ParsedDocument("source", List.of(chapter, reference, equationParagraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertEquals(Judgement.REVIEW, results.stream().filter(item -> item.ruleId().equals("EQ-03"))
        .findFirst().orElseThrow().judgement());
    assertEquals(Judgement.PASS, results.stream().filter(item -> item.ruleId().equals("EQ-04"))
        .findFirst().orElseThrow().judgement());
    assertEquals(Judgement.REVIEW, results.stream().filter(item -> item.ruleId().equals("EQ-07"))
        .findFirst().orElseThrow().judgement());
  }

  @Test
  void passesEquationVariablesWhenFollowingParagraphExplainsThem() {
    var chapter = chapterHeading();
    var equationSource = new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1);
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var variablesSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 18);
    var equationParagraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        20, false, equationParagraphSource)), 0, null, null, null, null, equationParagraphSource, 5);
    var variables = new ParagraphModel(List.of(new RunModel("式中：L0为长度，ρ为密度。", "Times New Roman", "宋体", null,
        24, false, variablesSource)), 480, null, null, 360, "auto", variablesSource, 200);
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        equationSource, "OMML", "x", "3-1", true, false);
    var document = new ParsedDocument("source", List.of(chapter, equationParagraph, variables), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-07")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void reportsUnreferencedEquationUnderEq04AndDuplicateNumberUnderEq05() {
    var equationParagraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 5);
    var firstEquation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 0), 0, 1), "OMML", "x", "3-1", true, false);
    var secondEquation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 1, 1), 0, 1), "OMML", "y", "3-1", true, false);
    var chapter = chapterHeading();
    var equationParagraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        20, false, equationParagraphSource)), 0, null, null, null, null, equationParagraphSource, 5);
    var document = new ParsedDocument("source", List.of(chapter, equationParagraph), List.of(), List.of(),
        List.of(), List.of(firstEquation, secondEquation), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("EQ-04")
        && item.judgement() == Judgement.FAIL && item.reasonCode().equals("EQUATION_NOT_REFERENCED")));
    assertTrue(results.stream().anyMatch(item -> item.ruleId().equals("EQ-05")
        && item.judgement() == Judgement.FAIL && item.reasonCode().equals("EQUATION_NUMBER_DUPLICATE")));
  }

  @Test
  void putsOpaqueEquationObjectsUnderAuxiliaryEq06() {
    var equation = new cn.luwneshacha.thesis.document.EquationModel(
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 1), "OLE", "embedded equation", "3-1", true, true);
    var paragraphSource = new SourceAddress("word/document.xml", List.of(0), 0, 5);
    var paragraph = new ParagraphModel(List.of(new RunModel("(3-1)", "Times New Roman", "宋体", null,
        20, false, paragraphSource)), 0, null, null, null, null, paragraphSource, 5);
    var document = new ParsedDocument("source", List.of(paragraph), List.of(), List.of(),
        List.of(), List.of(equation), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("EQ-06")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
  }

  @Test
  void reportsUncitedTableInBody() {
    var caption = paragraph("表1-1 数据", List.of(0, 2));
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "表1-1 数据", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), caption), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-01")).findFirst().orElseThrow();
    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void reportsUncitedLogicalTableOnlyOnceWhenItHasContinuationPages() {
    var captionSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 2);
    var continuationCaptionSource = new SourceAddress("word/document.xml", List.of(0, 5), 0, 3);
    var caption = paragraph("表6-6 数据", List.of(0, 2));
    var continuationCaption = paragraph("续表6-6", List.of(0, 5));
    var firstPage = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        captionSource, "表6-6 数据", 2, 2, true, true, true, false, true, false);
    var continuation = new TableModel(new SourceAddress("word/document.xml", List.of(0, 6), 0, 0),
        continuationCaptionSource, "续表6-6", 2, 2, true, true, true, false, true, false);
    var document = new ParsedDocument("source",
        List.of(chapterHeading(), caption, continuationCaption), List.of(), List.of(),
        List.of(firstPage, continuation), List.of(), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-01")).toList();
    assertEquals(1, results.size());
    assertEquals(Judgement.FAIL, results.get(0).judgement());
  }

  @Test
  void reviewsMissingThreeLineEvidenceInsteadOfTreatingRecommendationAsHardFailure() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        ADDRESS, "表1-1 数据", 2, 2, false, false, false, false, true, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-02")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("TABLE_BORDER_REVIEW", result.reasonCode());
  }

  @Test
  void reportsFigurePlacementAsReviewWhenCaptionExists() {
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "图1-1 示例", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("FIG-02")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("FIGURE_LAYOUT_REVIEW", result.reasonCode());
  }

  @Test
  void reportsFigurePlacementPassWhenParserHasVerifiedInlineCenteredLayout() {
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "图3-1 示例", 100, 200, true, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-02")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void reportsFigurePlacementFailureWhenImageParagraphIsNotCentered() {
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "图3-1 示例", 100, 200, true, false, true);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-02")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FIGURE_NOT_CENTERED", result.reasonCode());
  }

  @Test
  void ignoresCoverLogoWhenCheckingFigureCaptions() {
    var coverParagraph = paragraph("cover", List.of(0, 1));
    var chapter = paragraph("第一章 绪论", List.of(0, 2));
    var image = new ImageModel(coverParagraph.source(), null, null, 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(coverParagraph, chapter), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var figureResults = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().startsWith("FIG-")).toList();

    assertTrue(figureResults.isEmpty());
  }

  @Test
  void checksThatCaptionedFiguresAreReferencedInBodyText() {
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 18);
    var captionSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 9);
    var body = paragraph("系统结构如图1-1所示。", List.of(0, 2));
    var caption = paragraph("图1-1 系统结构图", List.of(0, 3));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 0),
        captionSource, "图1-1 系统结构图", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-01")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
    assertEquals(bodySource.elementPath(), body.source().elementPath());
  }

  @Test
  void reportsCaptionedFigureThatIsNotReferencedInBody() {
    var caption = paragraph("图2-1 系统结构图", List.of(0, 2));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "图2-1 系统结构图", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), caption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FIGURE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void reviewsGenericFigureReferenceWithoutAssumingWhichFigureItMeans() {
    var body = paragraph("系统工作人员总体流程如图所示。", List.of(0, 1));
    var caption = paragraph("图2-1 工作人员流程图", List.of(0, 2));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "图2-1 工作人员流程图", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-01")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
    assertEquals("FIGURE_REFERENCE_NEEDS_REVIEW", result.reasonCode());
  }

  @Test
  void doesNotLetNumberedFigureReferenceInSameParagraphHideAnUncitedFigure() {
    var body = paragraph("前文提到如图所示，具体见图2-1。", List.of(0, 1));
    var caption = paragraph("图1-1 工作人员流程图", List.of(0, 2));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 0),
        caption.source(), "图1-1 工作人员流程图", 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, caption), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("FIG-01")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("FIGURE_NOT_CITED_IN_BODY", result.reasonCode());
  }

  @Test
  void reviewsAnImageWhoseFigureCaptionCannotBeLocated() {
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        null, null, 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading()), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().startsWith("FIG-")).toList();

    assertEquals(1, results.size());
    assertEquals("FIGURE_CAPTION_UNRESOLVED", results.get(0).reasonCode());
    assertEquals(Judgement.REVIEW, results.get(0).judgement());
  }

  @Test
  void doesNotApplyFigureCaptionRulesToCodeScreenshotAfterCodeIntroduction() {
    var introduction = paragraph("核心代码如下所示。", List.of(0, 1));
    var image = new ImageModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        null, null, 100, 200, true, true);
    var document = new ParsedDocument("source", List.of(chapterHeading(), introduction), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(image));

    var figureResults = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().startsWith("FIG-")).toList();

    assertTrue(figureResults.isEmpty());
  }

  @Test
  void reportsTooFewReferencesAndUsesReferenceOrderRuleForDuplicates() {
    var first = new ReferenceEntryModel(ADDRESS, "1", "[1] A[M].", "M", false);
    var duplicate = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(1), 0, 1),
        "1", "[1] B[J].", "J", false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(first, duplicate), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("REF-05") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("REF-03") && r.judgement() == Judgement.FAIL));
  }

  @Test
  void ignoresBracketedNumbersThatAreNotAtSentenceEnd() {
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 30);
    var body = new ParagraphModel(List.of(
        new RunModel("Array [1] stores values", "Times New Roman", "宋体", null, 24, false, bodySource)),
        480, null, null, 360, "auto", bodySource, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body), List.of(), List.of());

    var citationResults = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("CITE-01") || r.ruleId().equals("REF-03")).toList();

    assertTrue(citationResults.isEmpty());
  }

  @Test
  void requiresBodyCitationsToBeSuperscriptAndIgnoresReferenceListNumbers() {
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var body = new ParagraphModel(List.of(
        new RunModel("这是一句正文[1]。", "Times New Roman", "宋体", null, 24, false, bodySource)),
        480, null, null, 360, "auto", bodySource, 200);
    var referenceHeading = paragraph("参考文献", List.of(0, 2));
    var referenceSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 20);
    var reference = new ParagraphModel(List.of(
        new RunModel("[1] 文献[M]. 出版社", "Times New Roman", "宋体", null, 24, false, referenceSource)),
        0, null, null, 360, "auto", referenceSource, 0);
    var document = new ParsedDocument("source", List.of(chapterHeading(), body, referenceHeading, reference),
        List.of(), List.of(), List.of(), List.of(),
        List.of(new ReferenceEntryModel(referenceSource, "1", "[1] 文献[M]. 出版社", "M", false)), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertEquals(1, results.stream().filter(r -> r.ruleId().equals("CITE-01")
        && r.judgement() == Judgement.FAIL).count());
    assertEquals(bodySource, results.stream().filter(r -> r.ruleId().equals("CITE-01")).findFirst().orElseThrow().source());
  }

  @Test
  void acceptsSuperscriptCitationSplitAcrossRuns() {
    var bodySource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var citation = new ParagraphModel(List.of(
        new RunModel("正文", "Times New Roman", "宋体", null, 24, false, bodySource),
        new RunModel("[", "Times New Roman", "宋体", null, 24, false, "superscript", bodySource),
        new RunModel("1", "Times New Roman", "宋体", null, 24, false, "superscript", bodySource),
        new RunModel("]", "Times New Roman", "宋体", null, 24, false, "superscript", bodySource)),
        480, null, null, 360, "auto", bodySource, 200);
    var document = new ParsedDocument("source", List.of(chapterHeading(), citation), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("CITE-01")).findFirst().orElseThrow();

    assertEquals(Judgement.PASS, result.judgement());
  }

  @Test
  void requiresReferencesToMatchFirstAppearanceOrderAndBeCited() {
    var citationSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 30);
    var citation = new ParagraphModel(List.of(
        new RunModel("正文引用[2,1]。", "Times New Roman", "宋体", null, 24, false, citationSource)),
        480, null, null, 360, "auto", citationSource, 200);
    var firstReferenceSource = new SourceAddress("word/document.xml", List.of(0, 3), 0, 15);
    var secondReferenceSource = new SourceAddress("word/document.xml", List.of(0, 4), 0, 15);
    var unusedReferenceSource = new SourceAddress("word/document.xml", List.of(0, 5), 0, 15);
    var document = new ParsedDocument("source", List.of(chapterHeading(), citation), List.of(), List.of(),
        List.of(), List.of(), List.of(
            new ReferenceEntryModel(firstReferenceSource, "1", "[1] A[M].", "M", false),
            new ReferenceEntryModel(secondReferenceSource, "2", "[2] B[J].", "J", false),
            new ReferenceEntryModel(unusedReferenceSource, "3", "[3] C[J].", "J", false)), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("REF-03") && r.judgement() == Judgement.FAIL).toList();

    assertEquals(List.of(firstReferenceSource, secondReferenceSource, unusedReferenceSource),
        results.stream().map(r -> r.source()).toList());
  }

  @Test
  void reviewsUnsupportedCitationSeparatorsInsteadOfCallingEntriesUncited() {
    var citationSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var citation = new ParagraphModel(List.of(
        new RunModel("Text [1;2].", "Times New Roman", "宋体", null, 24, false, citationSource)),
        480, null, null, 360, "auto", citationSource, 200);
    var firstReference = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 10),
        "1", "[1] A[M].", "M", false);
    var secondReference = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 10),
        "2", "[2] B[J].", "J", false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), citation), List.of(), List.of(),
        List.of(), List.of(), List.of(firstReference, secondReference), List.of());

    var referenceResults = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("REF-03")).toList();

    assertEquals(1, referenceResults.size());
    assertEquals(Judgement.REVIEW, referenceResults.get(0).judgement());
  }

  @Test
  void expandsNumberRangesWhenCheckingReferenceOrder() {
    var citationSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 20);
    var citation = new ParagraphModel(List.of(
        new RunModel("Text [1-2].", "Times New Roman", "宋体", null, 24, false, citationSource)),
        480, null, null, 360, "auto", citationSource, 200);
    var firstReference = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 3), 0, 10),
        "1", "[1] A[M].", "M", false);
    var secondReference = new ReferenceEntryModel(new SourceAddress("word/document.xml", List.of(0, 4), 0, 10),
        "2", "[2] B[J].", "J", false);
    var document = new ParsedDocument("source", List.of(chapterHeading(), citation), List.of(), List.of(),
        List.of(), List.of(), List.of(firstReference, secondReference), List.of());

    var referenceProblems = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("REF-03") && r.judgement() != Judgement.PASS).toList();

    assertTrue(referenceProblems.isEmpty());
  }

  @Test
  void checksPageNumberFooterEvidence() {
    var page = new PageNumberModel(new SourceAddress("word/footer1.xml", List.of(), 0, 0), true,
        false, "Arial", 22);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(page));

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertEquals(3, results.stream().filter(r -> r.ruleId().equals("PAGE-05")).count());
    assertTrue(results.stream().allMatch(r -> r.judgement() == Judgement.FAIL));
  }

  @Test
  void reviewsRomanSectionPageNumberFormat() {
    var section = new SectionModel(1474, 1474, 1474, 1474, ADDRESS, "upperRoman", 1);
    var document = new ParsedDocument("source", List.of(), List.of(section), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("PAGE-04")).findFirst().orElseThrow();

    assertEquals(Judgement.REVIEW, result.judgement());
  }

  @Test
  void reviewsChapterWithoutExplicitPageBreakEvidence() {
    var firstChapter = new HeadingBreakModel(ADDRESS, "第一章 绪论", false);
    var chapter = new HeadingBreakModel(ADDRESS, "第二章 测试", false);
    var document = new ParsedDocument("source", List.<ParagraphModel>of(), List.<SectionModel>of(), List.of(),
        List.<TableModel>of(), List.<cn.luwneshacha.thesis.document.EquationModel>of(),
        List.<ReferenceEntryModel>of(), List.<ImageModel>of(), List.<PageNumberModel>of(), List.of(firstChapter, chapter));

    var results = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("PAGE-03")).toList();

    assertEquals(1, results.size());
    assertEquals(Judgement.REVIEW, results.get(0).judgement());
  }
}
