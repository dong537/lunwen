package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoverRuleCheckerTest {
  @Test
  void recordsOnlyActuallyVisitedCoverCandidates() {
    var document = new ParsedDocument("source", List.of(
        paragraph("本科生毕业设计（论文）", List.of(0, 0)),
        paragraph("题目：毕业论文", List.of(0, 1)),
        paragraph("指导教师：李四", List.of(0, 2)),
        paragraph("2026年6月", List.of(0, 3)),
        paragraph("2026年6月", List.of(0, 4)),
        paragraph("摘要", List.of(0, 5))), List.of(), List.of());
    var checker = new CoverRuleChecker(new ParagraphFormatChecker());

    var evaluation = checker.evaluate(document, RuleSet.schoolV1());

    assertEquals(checker.check(document, RuleSet.schoolV1()), evaluation.results());
    for (String ruleId : List.of("COVER-01-FONT", "COVER-01-SIZE",
        "COVER-02-FONT", "COVER-02-SIZE", "COVER-03-FONT", "COVER-03-SIZE",
        "COVER-03-BOLD", "COVER-03-LABEL")) {
      assertEquals(new RuleRun(ruleId, "CoverRuleChecker", 1, 1, 0, null),
          run(evaluation, ruleId), ruleId);
    }
    for (String ruleId : List.of("COVER-04-FONT", "COVER-04-SIZE", "COVER-04-BOLD")) {
      assertEquals(new RuleRun(ruleId, "CoverRuleChecker", 2, 2, 0, null),
          run(evaluation, ruleId), ruleId);
    }
    assertEquals(new RuleRun("COVER-01-ALIGNMENT", "CoverRuleChecker", 1, 0, 1, null),
        run(evaluation, "COVER-01-ALIGNMENT"));
    assertEquals(new RuleRun("COVER-04-CONSISTENCY", "CoverRuleChecker", 1, 1, 0, null),
        run(evaluation, "COVER-04-CONSISTENCY"));
  }

  @Test
  void recordsOnePassForEachMatchingCoverDateAfterTheFirst() {
    var document = new ParsedDocument("source", List.of(
        paragraph("本科生毕业设计（论文）", List.of(0, 0)),
        paragraph("2026年6月", List.of(0, 1)),
        paragraph("2026年6月", List.of(0, 2)),
        paragraph("2026年6月", List.of(0, 3)),
        paragraph("摘要", List.of(0, 4))), List.of(), List.of());

    var evaluation = new CoverRuleChecker(new ParagraphFormatChecker())
        .evaluate(document, RuleSet.schoolV1());
    var decisions = evaluation.results().stream()
        .filter(result -> "COVER-04-CONSISTENCY".equals(result.ruleId())).toList();

    assertEquals(new RuleRun("COVER-04-CONSISTENCY", "CoverRuleChecker", 2, 2, 0, null),
        run(evaluation, "COVER-04-CONSISTENCY"));
    assertEquals(2, decisions.size());
    assertTrue(decisions.stream().allMatch(result -> result.judgement() == Judgement.PASS));
    assertEquals(List.of(List.of(0, 2), List.of(0, 3)),
        decisions.stream().map(result -> result.source().elementPath()).toList());
  }

  @Test
  void preservesMismatchAndMatchingDecisionsForLaterCoverDates() {
    var document = new ParsedDocument("source", List.of(
        paragraph("本科生毕业设计（论文）", List.of(0, 0)),
        paragraph("2026年6月", List.of(0, 1)),
        paragraph("2026年7月", List.of(0, 2)),
        paragraph("2026年6月", List.of(0, 3)),
        paragraph("摘要", List.of(0, 4))), List.of(), List.of());

    var evaluation = new CoverRuleChecker(new ParagraphFormatChecker())
        .evaluate(document, RuleSet.schoolV1());
    var decisions = evaluation.results().stream()
        .filter(result -> "COVER-04-CONSISTENCY".equals(result.ruleId())).toList();

    assertEquals(2, decisions.size());
    assertEquals(List.of(Judgement.FAIL, Judgement.PASS),
        decisions.stream().map(CheckResult::judgement).toList());
    assertEquals(new RuleRun("COVER-04-CONSISTENCY", "CoverRuleChecker", 2, 2, 0, null),
        run(evaluation, "COVER-04-CONSISTENCY"));
  }

  @Test
  void oneCoverDateHasNoComparisonCandidateAfterTheAbstractBoundary() {
    var document = new ParsedDocument("source", List.of(
        paragraph("本科生毕业设计（论文）", List.of(0, 0)),
        paragraph("2026年6月", List.of(0, 1)),
        paragraph("摘要", List.of(0, 2))), List.of(), List.of());
    var checker = new CoverRuleChecker(new ParagraphFormatChecker());

    var evaluation = checker.evaluate(document, RuleSet.schoolV1());

    assertEquals(new RuleRun("COVER-04-CONSISTENCY", "CoverRuleChecker", 0, 0, 0,
        "SINGLE_COVER_DATE"), run(evaluation, "COVER-04-CONSISTENCY"));
    assertTrue(evaluation.results().stream()
        .noneMatch(result -> "COVER-04-CONSISTENCY".equals(result.ruleId())));
    assertEquals(checker.check(document, RuleSet.schoolV1()), evaluation.results());
  }

  @Test
  void oneDateWithoutAnAbstractBoundaryDoesNotProveAnEmptyComparisonScope() {
    var document = new ParsedDocument("source", List.of(
        paragraph("本科生毕业设计（论文）", List.of(0, 0)),
        paragraph("2026年6月", List.of(0, 1))), List.of(), List.of());

    var evaluation = new CoverRuleChecker(new ParagraphFormatChecker())
        .evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.ruleRuns().stream()
        .noneMatch(run -> "COVER-04-CONSISTENCY".equals(run.ruleId())));
  }

  @Test
  void missingCoverTypeDoesNotInventFormattingEvidence() {
    var document = new ParsedDocument("source", List.of(
        paragraph("题目：毕业论文", List.of(0, 0)),
        paragraph("摘要", List.of(0, 1))), List.of(), List.of());

    var evaluation = new CoverRuleChecker(new ParagraphFormatChecker())
        .evaluate(document, RuleSet.schoolV1());

    assertTrue(evaluation.ruleRuns().isEmpty());
  }

  private static RuleRun run(RuleModule.Evaluation evaluation, String ruleId) {
    return evaluation.ruleRuns().stream().filter(item -> item.ruleId().equals(ruleId))
        .findFirst().orElseThrow();
  }

  @Test
  void acceptsCorrectTitleWhenLabelAndValueShareARunAfterLeadingAlignmentWhitespace() {
    String titleText = "\t题 目：基于 SpringBoot 的在线技能学习平台";
    var coverType = paragraph("本科生毕业设计（论文）", List.of(0, 0));
    var titleSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, titleText.length());
    var title = new ParagraphModel(List.of(new RunModel(titleText, "黑体", "黑体",
        "黑体", null, 36, null, false, null, titleSource)), null, null, null, null, null,
        titleSource, null);
    var abstractHeading = paragraph("摘要", List.of(0, 2));
    var document = new ParsedDocument("source", List.of(coverType, title, abstractHeading), List.of(), List.of());

    List<CheckResult> results = new CoverRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1());
    List<CheckResult> titleResults = results.stream()
        .filter(result -> result.ruleId().startsWith("COVER-02-"))
        .toList();

    assertEquals(2, titleResults.size(), results.toString());
    assertTrue(titleResults.stream().allMatch(result -> result.judgement() == Judgement.PASS),
        titleResults.toString());
  }

  @Test
  void keepsTitleValueOffsetAlignedWhenRunsPrecedeTheTitleLabel() {
    String label = "题 目：";
    String value = "基于 SpringBoot 的在线技能学习平台";
    var coverType = paragraph("本科生毕业设计（论文）", List.of(0, 0));
    var paragraphSource = new SourceAddress("word/document.xml", List.of(0, 1), 0,
        1 + label.length() + value.length());
    var title = new ParagraphModel(List.of(
        new RunModel("\t", null, null, null, null, null, null, false,
            null, new SourceAddress("word/document.xml", List.of(0, 1), 0, 1)),
        new RunModel(label, "黑体", "黑体", "黑体", null, 36,
            null, false, null, new SourceAddress("word/document.xml", List.of(0, 1), 1, 1 + label.length())),
        new RunModel(value, "黑体", "黑体", "黑体", null, 36,
            null, false, null, new SourceAddress("word/document.xml", List.of(0, 1), 1 + label.length(),
                1 + label.length() + value.length()))), null, null, null, null, null, paragraphSource, null);
    var document = new ParsedDocument("source", List.of(coverType, title,
        paragraph("摘要", List.of(0, 2))), List.of(), List.of());

    List<CheckResult> titleResults = new CoverRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().startsWith("COVER-02-"))
        .toList();

    assertEquals(2, titleResults.size(), titleResults.toString());
    assertTrue(titleResults.stream().allMatch(result -> result.judgement() == Judgement.PASS),
        titleResults.toString());
  }

  @Test
  void acceptsDigitsUsingTheFontSpecifiedForCoverFields() {
    String fieldText = "学号：2206181141";
    var coverType = paragraph("本科生毕业设计（论文）", List.of(0, 0));
    var fieldSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, fieldText.length());
    var field = new ParagraphModel(List.of(new RunModel(fieldText, "仿宋", "仿宋", "仿宋",
        null, 32, null, true, null, fieldSource)), null, null, null, null, null, fieldSource, null);
    var document = new ParsedDocument("source", List.of(coverType, field,
        paragraph("摘要", List.of(0, 2))), List.of(), List.of());

    List<CheckResult> fontResults = new CoverRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("COVER-03-FONT"))
        .toList();

    assertEquals(1, fontResults.size(), fontResults.toString());
    assertTrue(fontResults.stream().allMatch(result -> result.judgement() == Judgement.PASS),
        fontResults.toString());
  }

  @Test
  void checksIndependentDateInsideCoverTableAfterCoverIsRecognized() {
    ParagraphModel coverType = paragraph("本科生毕业设计（论文）", List.of(0, 0));
    ParagraphModel tableDate = paragraph("2026年6月", List.of(0, 1, 0, 0));
    ParagraphModel abstractHeading = paragraph("摘要", List.of(0, 2));
    ParsedDocument document = documentWithTable(coverType, tableDate, abstractHeading,
        List.of(0, 1));

    List<CheckResult> results = new CoverRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1());

    assertTrue(results.stream().anyMatch(result -> result.ruleId().startsWith("COVER-04-")
        && result.source().elementPath().equals(List.of(0, 1, 0, 0))),
        results.toString());
  }

  @Test
  void checksFormattingOfCoverFieldsStoredInTableCells() {
    var coverType = paragraph("本科生毕业设计（论文）", List.of(0, 0));
    String fieldText = "学生姓名：张三";
    var fieldSource = new SourceAddress("word/document.xml", List.of(0, 1, 0, 0), 0, fieldText.length());
    var field = new ParagraphModel(List.of(new RunModel(fieldText, "仿宋", "仿宋", "仿宋",
        null, 32, null, true, null, fieldSource)), null, null, null, null, null, fieldSource, null);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        null, null, 1, 1, false, false, false, false, false, false);
    var document = new ParsedDocument("source", List.of(coverType, field,
        paragraph("摘要", List.of(0, 2))), List.of(), List.of(), List.of(table), List.of(), List.of());

    List<CheckResult> fieldResults = new CoverRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().startsWith("COVER-03-"))
        .toList();

    assertEquals(3, fieldResults.size(), fieldResults.toString());
    assertTrue(fieldResults.stream().allMatch(result -> result.judgement() == Judgement.PASS),
        fieldResults.toString());
  }

  @Test
  void doesNotUseSchoolNameInsideCoverTableAsTheCoverSchoolName() {
    ParagraphModel tableSchool = paragraph("山东石油化工学院", List.of(0, 0, 0, 0));
    ParagraphModel coverType = paragraph("本科生毕业设计（论文）", List.of(0, 1));
    ParagraphModel abstractHeading = paragraph("摘要", List.of(0, 2));
    ParsedDocument document = documentWithTable(tableSchool, coverType, abstractHeading,
        List.of(0, 0));

    List<CheckResult> results = new CoverRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1());

    assertTrue(results.stream().noneMatch(result -> result.source().elementPath().equals(List.of(0, 0, 0, 0))),
        results.toString());
  }

  @Test
  void doesNotActivateCoverChecksFromCoverTypeInsideATable() {
    ParagraphModel tableCoverType = paragraph("本科生毕业设计（论文）", List.of(0, 0, 0, 0));
    ParagraphModel abstractHeading = paragraph("摘要", List.of(0, 1));
    ParsedDocument document = documentWithTable(tableCoverType, abstractHeading, null,
        List.of(0, 0));

    List<CheckResult> results = new CoverRuleChecker(new ParagraphFormatChecker())
        .check(document, RuleSet.schoolV1());

    assertTrue(results.isEmpty(), results.toString());
  }

  private static ParsedDocument documentWithTable(ParagraphModel first, ParagraphModel second,
      ParagraphModel third, List<Integer> tablePath) {
    List<ParagraphModel> paragraphs = third == null
        ? List.of(first, second)
        : List.of(first, second, third);
    TableModel table = new TableModel(new SourceAddress("word/document.xml", tablePath, 0, 0),
        null, null, 1, 1, false, false, false, false, false, false);
    return new ParsedDocument("source", paragraphs, List.of(), List.of(), List.of(table),
        List.of(), List.of(), List.of());
  }

  private static ParagraphModel paragraph(String text, List<Integer> path) {
    SourceAddress source = new SourceAddress("word/document.xml", path, 0, text.length());
    return new ParagraphModel(List.of(new RunModel(text, "Times New Roman", "宋体", null,
        24, false, source)), null, null, null, null, null, source, null);
  }
}
