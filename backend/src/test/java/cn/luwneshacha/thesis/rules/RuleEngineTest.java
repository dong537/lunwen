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
    var document = new ParsedDocument("source", List.of(chapterHeading(), paragraph),
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
    var source = new SourceAddress("word/document.xml", List.of(0, 0), 0, 6);
    var run = new RunModel("第1章 绪论", "Times New Roman", "黑体", null, 32, true, source);
    return new ParagraphModel(List.of(run), 0, null, null, null, null, source, 0);
  }

  @Test
  void treatsAuxiliaryTableLinesAsReviewInsteadOfFailure() {
    var table = new TableModel(ADDRESS, ADDRESS, "表1-1 数据", 3, 2,
        true, true, true, true, true, false);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("TAB-03")).findFirst().orElseThrow();

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
  void checksSecondLevelHeadingFontSizeAndBold() {
    var heading = new ParagraphModel(
        List.of(new RunModel("1.1 标题", "Arial", "宋体", null, 24, false, ADDRESS)),
        0, null, null, 360, "auto", ADDRESS, 0);
    var document = new ParsedDocument("source", List.of(heading), List.of(), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("HEADING-FONT") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("HEADING-SIZE") && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("HEADING-BOLD") && r.judgement() == Judgement.FAIL));
  }

  @Test
  void reportsCaptionBelowTableAsPositionError() {
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(1), 0, 0),
        ADDRESS, "表1-2 表题", 2, 2, true, true, true, false, false, true);
    var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
        List.of(table), List.of(), List.of(), List.of());

    var result = new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("TAB-02")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, result.judgement());
    assertEquals("TABLE_CAPTION_BELOW", result.reasonCode());
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
