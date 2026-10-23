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
