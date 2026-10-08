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
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleEngineTest {
  private static final SourceAddress ADDRESS = new SourceAddress("word/document.xml", List.of(0), 0, 2);

  @Test
  void reportsPassForSchoolV1Formatting() {
    var run = new RunModel("正文A", "Times New Roman", "宋体", null, 24, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, null, null, ADDRESS, 200);
    var document = new ParsedDocument("source", List.of(paragraph),
        List.of(new SectionModel(1474, 1474, 1474, 1474, ADDRESS)), List.of());

    var results = new RuleEngine().check(document, RuleSet.schoolV1());

    assertTrue(results.stream().allMatch(result -> result.judgement() == Judgement.PASS));
  }

  @Test
  void reportsFailForWrongMarginAndFontSize() {
    var run = new RunModel("正文A", "Arial", "宋体", null, 22, false, ADDRESS);
    var paragraph = new ParagraphModel(List.of(run), 480, null, null, null, null, ADDRESS, 200);
    var document = new ParsedDocument("source", List.of(paragraph),
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
    var document = new ParsedDocument("source", List.of(paragraph),
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
    var document = new ParsedDocument("source", List.of(paragraph),
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
}
