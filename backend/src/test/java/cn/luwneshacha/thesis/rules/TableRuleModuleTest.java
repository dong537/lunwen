package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.EquationModel;
import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;
import org.junit.jupiter.api.Test;

class TableRuleModuleTest {
  @Test
  void checksCaptionCitationAndTableSequenceForAThesisTable() {
    var chapter = paragraph("第1章 绪论", List.of(0, 0), "left");
    var caption = paragraph("表1-1 测试结果", List.of(0, 1), "center");
    var citation = paragraph("具体结果见表1-1。", List.of(0, 3), "left");
    var tableSource = source(2);
    var table = table(tableSource, caption.source(), "表1-1 测试结果", 2, 2,
        List.of("项目", "结果"));
    var document = new ParsedDocument("source", List.of(chapter, caption, citation), List.of(), List.of(),
        List.of(table), List.of(), List.of());

    var findings = new TableRuleModule().check(document, RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(item -> item.ruleId().equals("TAB-01")
        && item.judgement() == Judgement.PASS));
    assertTrue(findings.stream().anyMatch(item -> item.ruleId().equals("TAB-03")
        && item.judgement() == Judgement.PASS));
    assertTrue(findings.stream().anyMatch(item -> item.ruleId().equals("CAPTION-FONT")
        && item.judgement() == Judgement.PASS));
    assertTrue(findings.stream().anyMatch(item -> item.ruleId().equals("CAPTION-SIZE")
        && item.judgement() == Judgement.PASS));
    assertFalse(findings.stream().anyMatch(item -> item.reasonCode().equals("TABLE_NUMBER_SEQUENCE_MISMATCH")));
  }

  @Test
  void preservesLegacyCaptionSizeFailureReason() {
    var chapter = paragraph("第1章 绪论", List.of(0, 0), "left");
    var caption = paragraph("表1-1 测试结果", List.of(0, 1), "center", 20);
    var table = table(source(2), caption.source(), "表1-1 测试结果", 2, 2,
        List.of("项目", "结果"));
    var document = new ParsedDocument("source", List.of(chapter, caption), List.of(), List.of(),
        List.of(table), List.of(), List.of());

    var sizeFinding = new TableRuleModule().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("CAPTION-SIZE")).findFirst().orElseThrow();

    assertEquals(Judgement.FAIL, sizeFinding.judgement());
    assertEquals("", sizeFinding.reasonCode());
  }

  @Test
  void marksUnresolvedTableStyleBordersAsReviewInsteadOfFailure() {
    var caption = paragraph("表1-1 测试结果", List.of(0, 0), "center");
    var table = new TableModel(source(1), caption.source(), "表1-1 测试结果", 2, 2,
        false, false, false, false, true, false, false, List.of(), List.of(),
        List.of("项目", "结果"), null, null, false, true, null, false, false, true, false);
    var document = new ParsedDocument("source", List.of(caption), List.of(), List.of(),
        List.of(table), List.of(), List.of());

    var borderFinding = new TableRuleModule().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("TAB-02")).findFirst().orElseThrow();

    assertEquals("TABLE_BORDER_EVIDENCE_UNRESOLVED", borderFinding.reasonCode());
    assertEquals(Judgement.REVIEW, borderFinding.judgement());
  }

  @Test
  void reportsOnlyTheMissingBordersForNormalAndContinuationTables() {
    record BorderCase(boolean top, boolean middle, boolean bottom, String missingLines) {}
    var cases = List.of(
        new BorderCase(false, false, false, "顶线、表头下横线、底线"),
        new BorderCase(true, false, false, "表头下横线、底线"),
        new BorderCase(false, true, false, "顶线、底线"),
        new BorderCase(true, true, false, "底线"),
        new BorderCase(false, false, true, "顶线、表头下横线"),
        new BorderCase(true, false, true, "表头下横线"),
        new BorderCase(false, true, true, "顶线"));
    for (String caption : List.of("表1-1 测试结果", "续表1-1")) {
      for (var borderCase : cases) {
        var table = new TableModel(source(1), source(0), caption, 2, 2,
            borderCase.top(), borderCase.middle(), borderCase.bottom(), false, true, false);
        var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
            List.of(table), List.of(), List.of());

        var findings = new TableRuleModule().check(document, RuleSet.schoolV1()).stream()
            .filter(item -> item.ruleId().equals("TAB-02")).toList();

        assertEquals(1, findings.size(), caption + ": " + borderCase);
        var finding = findings.get(0);
        assertEquals(Judgement.FAIL, finding.judgement());
        assertEquals(caption.startsWith("续表")
            ? "TABLE_CONTINUATION_BORDER_INVALID" : "TABLE_BORDER_INVALID", finding.reasonCode());
        assertEquals(borderCase.missingLines(), finding.expected(), caption + ": " + borderCase);
        assertEquals("缺少符合规范的" + borderCase.missingLines(), finding.actual(), caption + ": " + borderCase);
        assertEquals(table.source(), finding.source());
      }
    }
  }

  @Test
  void doesNotReportMissingBordersWhenAllThreeLinesArePresent() {
    for (String caption : List.of("表1-1 测试结果", "续表1-1")) {
      var table = new TableModel(source(1), source(0), caption, 2, 2,
          true, true, true, false, true, false);
      var document = new ParsedDocument("source", List.of(), List.of(), List.of(),
          List.of(table), List.of(), List.of());

      assertFalse(new TableRuleModule().check(document, RuleSet.schoolV1()).stream()
          .anyMatch(item -> item.ruleId().equals("TAB-02")), caption);
    }
  }

  @Test
  void comparesContinuationHeaderWithItsAdjacentOriginalTable() {
    var findings = continuationFindings(List.of("项目", "结果"));

    assertFalse(findings.stream().anyMatch(item -> item.ruleId().equals("TAB-08")), findings.toString());
  }

  @Test
  void reportsContinuationHeaderThatDiffersFromItsAdjacentOriginalTable() {
    var finding = continuationFindings(List.of("项目", "结果不同")).stream()
        .filter(item -> item.ruleId().equals("TAB-08")).findFirst().orElseThrow();

    assertEquals("TABLE_CONTINUATION_HEADER_MISMATCH", finding.reasonCode());
    assertEquals("项目 | 结果", finding.expected());
    assertEquals("项目 | 结果不同", finding.actual());
  }

  @Test
  void excludesCoverCodeAndEquationTablesButRequiresEvidenceForFragments() {
    var cover = paragraph("学生姓名：张三", List.of(0, 0), "left");
    var chapter = paragraph("第1章 绪论", List.of(0, 1), "left");
    var coverTable = table(source(0), null, null, 2, 2, List.of());
    var codeTable = table(source(2), null, null, 1, 1,
        List.of("@Controller public class Demo { return service.find(); }"));
    var equationTableSource = source(3);
    var equationTable = table(equationTableSource, null, null, 1, 2, List.of("x=1", "(1-1)"));
    var equation = new EquationModel(new SourceAddress("word/document.xml", List.of(0, 3, 0, 0), 0, 1),
        "OMML", "x=1", "1-1", true, false, java.util.Set.of("oMath", "eqArr"), java.util.Set.of(),
        null, new SourceAddress("word/document.xml", List.of(0, 3, 0, 1), 0, 5));
    var originalCaption = paragraph("表1-1 测试结果", List.of(0, 4), "center");
    var original = table(source(5), originalCaption.source(), "表1-1 测试结果", 3, 2,
        List.of("项目", "结果"));
    var fragmentSource = source(6);
    var fragment = table(fragmentSource, null, null, 1, 2, List.of("项目", "结果"));
    var continuationCaption = paragraph("续表1-1", List.of(0, 7), "right");
    var continuation = table(source(8), continuationCaption.source(), "续表1-1", 2, 2,
        List.of("项目", "结果不一致"));
    var citation = paragraph("具体结果见表1-1。", List.of(0, 9), "left");
    var document = new ParsedDocument("source",
        List.of(cover, chapter, originalCaption, continuationCaption, citation), List.of(), List.of(),
        List.of(coverTable, codeTable, equationTable, original, fragment, continuation), List.of(equation), List.of());

    var findings = new TableRuleModule().check(document, RuleSet.schoolV1());

    for (TableModel excluded : List.of(coverTable, codeTable, equationTable)) {
      assertFalse(findings.stream().anyMatch(item -> item.ruleId().startsWith("TAB-")
          && item.source() != null && item.source().equals(excluded.source())), excluded.source().toString());
    }
    assertTrue(findings.stream().anyMatch(item -> item.ruleId().equals("TAB-08")
        && item.source().equals(continuationCaption.source())));
    assertTrue(findings.stream().anyMatch(item -> item.reasonCode().equals("TABLE_CAPTION_MISSING")
        && fragment.source().equals(item.source())), findings.toString());
    assertTrue(findings.stream().anyMatch(item -> item.reasonCode().equals("TABLE_CONTINUATION_ORIGINAL_MISSING")),
        findings.toString());
  }

  @Test
  void usesCodeLayoutEvidenceToExcludeCodeListingTableWithoutCodeLikeHeaderText() {
    var chapter = paragraph("第1章 绪论", List.of(0, 0), "left");
    var source = source(2);
    var codeListing = new TableModel(source, null, null, 3, 1,
        false, false, false, false, false, false, false, List.of(), List.of(),
        List.of("源代码"), null, null, false, false, null, false, true, true, true,
        List.of());
    var document = new ParsedDocument("source", List.of(chapter), List.of(), List.of(),
        List.of(codeListing), List.of(), List.of());

    var findings = new TableRuleModule().check(document, RuleSet.schoolV1());

    assertFalse(findings.stream().anyMatch(item -> item.ruleId().startsWith("TAB-")
        && source.equals(item.source())), findings.toString());
  }

  @Test
  void tableSequenceIgnoresCaptionSpacingErrorsWhenReadingNumbers() {
    var chapter = paragraph("第4章 结果", List.of(0, 0), "left");
    var paragraphs = new java.util.ArrayList<ParagraphModel>();
    paragraphs.add(chapter);
    var tables = new java.util.ArrayList<TableModel>();
    for (int number = 1; number <= 8; number++) {
      String spacing = number == 6 || number == 7 ? "  " : " ";
      String captionText = "表4-" + number + spacing + "测试表";
      var caption = paragraph(captionText, List.of(0, number), "center");
      paragraphs.add(caption);
      tables.add(table(source(100 + number), caption.source(), captionText, 2, 2,
          List.of("项目", "结果")));
    }
    var document = new ParsedDocument("source", paragraphs, List.of(), List.of(), tables,
        List.of(), List.of());

    var findings = new TableRuleModule().check(document, RuleSet.schoolV1());

    assertFalse(findings.stream().anyMatch(item -> item.reasonCode().equals("TABLE_NUMBER_SEQUENCE_MISMATCH")),
        findings.toString());
    assertEquals(2, findings.stream()
        .filter(item -> item.reasonCode().equals("TABLE_CAPTION_NUMBER_FORMAT"))
        .count());
  }

  private static TableModel table(SourceAddress source, SourceAddress captionSource, String caption,
      int rows, int columns, List<String> headers) {
    return new TableModel(source, captionSource, caption, rows, columns,
        true, true, true, false, true, false, false, List.of(), List.of(), headers,
        null, null, false, false);
  }

  private static List<CheckResult> continuationFindings(List<String> continuationHeaders) {
    var chapter = paragraph("第1章 绪论", List.of(0, 0), "left");
    var originalCaption = paragraph("表1-1 测试结果", List.of(0, 1), "center");
    var continuationCaption = paragraph("续表1-1", List.of(0, 4), "right");
    var original = table(source(2), originalCaption.source(), "表1-1 测试结果", 3, 2,
        List.of("项目", "结果"));
    var continuation = table(source(5), continuationCaption.source(), "续表1-1", 2, 2,
        continuationHeaders);
    var citation = paragraph("具体结果见表1-1。", List.of(0, 6), "left");
    var document = new ParsedDocument("source",
        List.of(chapter, originalCaption, continuationCaption, citation), List.of(), List.of(),
        List.of(original, continuation), List.of(), List.of());

    return new TableRuleModule().check(document, RuleSet.schoolV1());
  }

  private static ParagraphModel paragraph(String text, List<Integer> path, String alignment) {
    return paragraph(text, path, alignment, 21);
  }

  private static ParagraphModel paragraph(String text, List<Integer> path, String alignment, int size) {
    var source = new SourceAddress("word/document.xml", path, 0, text.length());
    var run = new RunModel(text, "Times New Roman", "Times New Roman", "宋体", null,
        size, null, false, null, source);
    return new ParagraphModel(List.of(run), null, null, null, 360, "auto", source,
        null, null, alignment);
  }

  private static SourceAddress source(int index) {
    return new SourceAddress("word/document.xml", List.of(0, index), 0, 0);
  }
}
