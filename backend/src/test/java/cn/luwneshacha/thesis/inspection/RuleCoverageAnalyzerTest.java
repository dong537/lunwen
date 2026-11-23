package cn.luwneshacha.thesis.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.rules.Applicability;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.ExecutionStatus;
import cn.luwneshacha.thesis.rules.Judgement;
import cn.luwneshacha.thesis.rules.RuleRun;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuleCoverageAnalyzerTest {
  @Test
  void catalogRowsArePresentAndMissingRulesAreNotReportedAsPass() {
    var rows = new RuleCoverageAnalyzer().analyzeWithCatalog(List.of());

    assertEquals(RuleCatalog.entries().size(), rows.size());
    var bodyLine = rows.stream().filter(row -> row.ruleId().equals("BODY-LINE")).findFirst().orElseThrow();
    assertEquals("未产生结果", bodyLine.status());
    assertEquals("无结果证据", bodyLine.executionStatus());
    assertEquals(0, bodyLine.total());
  }

  @Test
  void completeRunCanProveASilentApplicableRuleWasChecked() {
    var rows = new RuleCoverageAnalyzer().analyzeWithCatalog(List.of(), List.of(
        new RuleRun("NUMBERING-01", "AutomaticNumberingRuleModule", 1, 1, 0, null)));

    var numbering = row(rows, "NUMBERING-01");
    assertEquals(0, numbering.total());
    assertEquals("已执行且无错误", numbering.status());
    assertEquals("已执行", numbering.executionStatus());
    assertEquals("适用", numbering.applicability());
    assertEquals("未产生结果", row(rows, "BODY-LINE").status());
  }

  @Test
  void oneCoverDateCanProveConsistencyHasNoComparisonCandidate() {
    var run = new RuleRun("COVER-04-CONSISTENCY", "CoverRuleChecker",
        0, 0, 0, "SINGLE_COVER_DATE");
    var coverage = row(new RuleCoverageAnalyzer().analyzeWithCatalog(List.of(), List.of(run)),
        "COVER-04-CONSISTENCY");

    assertEquals("不适用", coverage.status());
    assertEquals("已执行", coverage.executionStatus());
  }

  @Test
  void automaticWrapUncertaintyKeepsEnglishKeywordAlignmentCoverageIncomplete() {
    var review = new CheckResult("EN-03-ALIGNMENT", Applicability.APPLICABLE,
        ExecutionStatus.EXECUTED, Judgement.REVIEW, "检查换行后的关键词起始位置",
        "DOCX XML 无法确认 Word/WPS 是否发生自动换行", "ENGLISH_KEYWORD_AUTO_WRAP_UNKNOWN", null);
    var run = new RuleRun("EN-03-ALIGNMENT", "AbstractRuleChecker", 1, 0, 1, null);

    var coverage = row(new RuleCoverageAnalyzer().analyzeWithCatalog(List.of(review), List.of(run)),
        "EN-03-ALIGNMENT");

    assertEquals(1, coverage.review());
    assertEquals("证据不足，检查范围未确认", coverage.status());
    assertEquals("已执行", coverage.executionStatus());
    assertEquals("适用", coverage.applicability());
  }

  @Test
  void resultsDoNotClaimWholeRuleCoverageWithoutCompleteRunEvidence() {
    var analyzer = new RuleCoverageAnalyzer();
    var pass = result("BODY-LINE", Judgement.PASS, "1.5 倍行距");
    var fail = result("BODY-FONT", Judgement.FAIL, "小四号宋体");
    var partial = new RuleRun("BODY-LINE", "BodyFormatRuleChecker", 2, 1, 1, null);

    var rows = analyzer.analyzeWithCatalog(List.of(pass, fail), List.of(partial));
    assertEquals("有检查结果，检查范围未确认", row(rows, "BODY-LINE").status());
    assertEquals("已发现问题，检查范围未确认", row(rows, "BODY-FONT").status());

    var complete = new RuleRun("BODY-LINE", "BodyFormatRuleChecker", 1, 1, 0, null);
    assertEquals("已执行且无错误",
        row(analyzer.analyzeWithCatalog(List.of(pass), List.of(complete)), "BODY-LINE").status());
  }

  @Test
  void multiOwnerRuleRequiresEveryOwnerAndNoUnknownCandidate() {
    var figureCaption = new RuleRun("CAPTION-FONT", "ImageRuleChecker", 2, 2, 0, null);
    var tableCaption = new RuleRun("CAPTION-FONT", "TableRuleModule", 1, 1, 0, null);
    var partialTableCaption = new RuleRun("CAPTION-FONT", "TableRuleModule", 1, 0, 1, null);
    var analyzer = new RuleCoverageAnalyzer();

    assertEquals("未产生结果", row(analyzer.analyzeWithCatalog(List.of(),
        List.of(figureCaption)), "CAPTION-FONT").status());
    assertEquals("未产生结果", row(analyzer.analyzeWithCatalog(List.of(),
        List.of(figureCaption, partialTableCaption)), "CAPTION-FONT").status());
    assertEquals("已执行且无错误", row(analyzer.analyzeWithCatalog(List.of(),
        List.of(figureCaption, tableCaption)), "CAPTION-FONT").status());
  }

  @Test
  void zeroCandidatesRequireAnApprovedReasonEvenWhenAnotherOwnerHasCandidates() {
    var noImages = new RuleRun("FIG-01", "ImageRuleChecker", 0, 0, 0,
        "NO_IMAGES_IN_DOCUMENT");
    var rows = new RuleCoverageAnalyzer().analyzeWithCatalog(List.of(), List.of(noImages));

    assertEquals("未产生结果", row(rows, "FIG-01").status());
    assertEquals("无结果证据", row(rows, "FIG-01").executionStatus());
    assertThrows(IllegalArgumentException.class,
        () -> new RuleRun("FIG-01", "ImageRuleChecker", 0, 0, 0, null));
  }

  @Test
  void zeroCandidateReasonBelongsToTheSpecificRuleOwner() {
    var bodyAbsent = new RuleRun("HEADING-FONT", "BodyFormatRuleChecker",
        0, 0, 0, "NO_EMBEDDED_HEADING_IN_RESOLVED_BODY");
    var ordinaryHeading = new RuleRun("HEADING-FONT", "HeadingDocumentRuleModule",
        1, 1, 0, null);
    var analyzer = new RuleCoverageAnalyzer();

    assertEquals("已执行且无错误", row(analyzer.analyzeWithCatalog(List.of(),
        List.of(bodyAbsent, ordinaryHeading)), "HEADING-FONT").status());
    var wrongOwner = new RuleRun("HEADING-FONT", "HeadingDocumentRuleModule",
        0, 0, 0, "NO_EMBEDDED_HEADING_IN_RESOLVED_BODY");
    assertEquals("未产生结果", row(analyzer.analyzeWithCatalog(List.of(),
        List.of(wrongOwner, new RuleRun("HEADING-FONT", "BodyFormatRuleChecker",
            1, 1, 0, null))), "HEADING-FONT").status());
  }

  @Test
  void confirmedOptionalAppendixAbsenceCanCloseOnlyAppendixRules() {
    for (String pair : List.of("APP-01@AppendixRuleModule",
        "APP-02-BLANK@AppendixRuleModule", "APP-03@AppendixRuleModule",
        "APP-02-SPACING@HeadingDocumentRuleModule",
        "APP-02-FONT@HeadingDocumentRuleModule",
        "APP-02-SIZE@HeadingDocumentRuleModule",
        "APP-02-ALIGNMENT@HeadingDocumentRuleModule")) {
      String[] ids = pair.split("@");
      var run = new RuleRun(ids[0], ids[1], 0, 0, 0,
          "OPTIONAL_APPENDIX_ABSENT_AFTER_END_MATTER_SCAN");
      assertEquals("不适用", row(new RuleCoverageAnalyzer().analyzeWithCatalog(
          List.of(), List.of(run)), ids[0]).status(), pair);
    }
  }

  @Test
  void confirmedOptionalContentsScopesNeedTheirOwnReasons() {
    for (String ruleId : List.of("EN-TOC-01-BOLD", "EN-TOC-01-ALIGNMENT",
        "EN-TOC-01-FONT", "EN-TOC-01-SIZE", "EN-TOC-01-LINE")) {
      var run = new RuleRun(ruleId, "ContentsRuleChecker", 0, 0, 0,
          "NO_ENGLISH_CONTENTS_TITLE");
      assertEquals("不适用", row(new RuleCoverageAnalyzer().analyzeWithCatalog(
          List.of(), List.of(run)), ruleId).status(), ruleId);
    }
    var spacing = new RuleRun("TOC-SPACING", "ContentsRuleChecker", 0, 0, 0,
        "NO_NUMBERED_TOC_ENTRIES");
    assertEquals("不适用", row(new RuleCoverageAnalyzer().analyzeWithCatalog(
        List.of(), List.of(spacing)), "TOC-SPACING").status());
  }

  @Test
  void classifiesRequiredSectionsUnderTheirOwnReportModule() {
    var rows = new RuleCoverageAnalyzer().analyzeWithCatalog(List.of());

    assertEquals("必需章节", row(rows, "SECTION-REQUIRED").module());
  }

  @Test
  void catalogIncludesEveryObjectAndCitationModule() {
    var rows = new RuleCoverageAnalyzer().analyzeWithCatalog(List.of());

    assertModule(rows, "EQ-01", "公式");
    assertModule(rows, "FIG-01", "插图");
    assertModule(rows, "TAB-01", "表格");
    assertModule(rows, "CITE-01", "引用");
    assertModule(rows, "ZH-01-SPACING", "摘要");
  }

  @Test
  void reportsCaptionRulesUnderTheSharedFigureAndTableModule() {
    var rows = new RuleCoverageAnalyzer().analyzeWithCatalog(List.of(
        result("CAPTION-FONT", Judgement.FAIL, "表题字体"),
        result("CAPTION-FONT", Judgement.PASS, "图题字体"),
        result("CAPTION-SIZE", Judgement.FAIL, "表题字号")));

    var font = row(rows, "CAPTION-FONT");
    var size = row(rows, "CAPTION-SIZE");

    assertEquals("图表题", font.module());
    assertEquals("图题和表题", font.scope());
    assertEquals(2, font.total());
    assertEquals(1, font.passed());
    assertEquals(1, font.failed());
    assertEquals("图表题", size.module());
    assertEquals("图题和表题", size.scope());
  }

  @Test
  void catalogKeepsInspectionAndAnnotationLimitationsSeparate() {
    var rows = new RuleCoverageAnalyzer().analyzeWithCatalog(List.of());
    var page = row(rows, "PAGE-05");
    var equation = row(rows, "EQ-06");

    assertTrue(page.inspectionLimitation().contains("实际显示"));
    assertTrue(page.annotationLimitation().contains("页脚"));
    assertTrue(equation.inspectionLimitation().contains("MathType"));
  }

  @Test
  void statusSeparatesPassFailAndReviewEvidence() {
    var pass = result("BODY-LINE", Judgement.PASS, "1.5 倍行距");
    var fail = result("BODY-FONT", Judgement.FAIL, "小四号宋体");
    var review = result("EQ-03", Judgement.REVIEW, "无法确认公式编号位置");

    var rows = new RuleCoverageAnalyzer().analyzeWithCatalog(List.of(pass, fail, review));
    var passRow = row(rows, "BODY-LINE");
    var failRow = row(rows, "BODY-FONT");
    var reviewRow = row(rows, "EQ-03");

    assertEquals("有检查结果，检查范围未确认", passRow.status());
    assertEquals("已发现问题，检查范围未确认", failRow.status());
    assertEquals("证据不足，检查范围未确认", reviewRow.status());
    assertEquals("已执行", reviewRow.executionStatus());
    assertEquals(1, reviewRow.review());
    assertTrue(reviewRow.applicability().contains("适用"));
  }

  @Test
  void doesNotCallNotApplicableChecksErrorFree() {
    var notApplicable = new CheckResult("BODY-LINE", Applicability.NOT_APPLICABLE,
        ExecutionStatus.EXECUTED, Judgement.PASS, "1.5 倍行距", "不适用", "TEST", null);

    var row = row(new RuleCoverageAnalyzer().analyzeWithCatalog(List.of(notApplicable)), "BODY-LINE");

    assertEquals("不适用", row.applicability());
    assertEquals("已判断部分内容不适用，检查范围未确认", row.status());
  }

  @Test
  void positiveCandidatesCannotAllBeDismissedAsNotApplicable() {
    var notApplicable = new CheckResult("BODY-LINE", Applicability.NOT_APPLICABLE,
        ExecutionStatus.EXECUTED, Judgement.PASS, "1.5 倍行距", "不适用", "TEST", null);
    var run = new RuleRun("BODY-LINE", "BodyFormatRuleChecker", 1, 1, 0, null);

    var row = row(new RuleCoverageAnalyzer().analyzeWithCatalog(List.of(notApplicable),
        List.of(run)), "BODY-LINE");
    assertEquals("已判断部分内容不适用，检查范围未确认", row.status());
  }

  @Test
  void doesNotCallUnknownApplicabilityErrorFree() {
    var unknown = new CheckResult("BODY-LINE", Applicability.UNKNOWN,
        ExecutionStatus.EXECUTED, Judgement.PASS, "1.5 倍行距", "无法确认", "TEST", null);

    var row = row(new RuleCoverageAnalyzer().analyzeWithCatalog(List.of(unknown)), "BODY-LINE");

    assertEquals("适用性未知", row.applicability());
    assertEquals("适用性及检查范围未确认", row.status());
  }

  @Test
  void unknownRuleIdsAreReportedAsUnregisteredInsteadOfOther() {
    var unknown = result("NEW-RULE", Judgement.FAIL, "expected");
    var row = row(new RuleCoverageAnalyzer().analyzeWithCatalog(List.of(unknown)), "NEW-RULE");

    assertEquals("未登记规则", row.module());
    assertEquals("已执行并发现问题", row.status());
    assertTrue(row.scope().contains("目录未登记"));
    assertTrue(row.ruleDescription().contains("请确认编号有效"));
    assertTrue(row.evidenceSource().contains("规则依据尚未登记"));
    assertTrue(row.inspectionLimitation().contains("检查边界"));
    assertTrue(row.annotationLimitation().contains("待确认"));
    assertEquals(false, row.annotationSupported());
  }

  private static CheckResult result(String ruleId, Judgement judgement, String expected) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        judgement, expected, "actual", "TEST", null);
  }

  private static RuleCoverage row(List<RuleCoverage> rows, String ruleId) {
    return rows.stream().filter(row -> row.ruleId().equals(ruleId)).findFirst().orElseThrow();
  }

  private static void assertModule(List<RuleCoverage> rows, String ruleId, String module) {
    var coverage = row(rows, ruleId);
    assertEquals(module, coverage.module());
    assertEquals("未产生结果", coverage.status());
  }
}
