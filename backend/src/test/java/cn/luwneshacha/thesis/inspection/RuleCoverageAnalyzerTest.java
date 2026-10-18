package cn.luwneshacha.thesis.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.rules.Applicability;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.ExecutionStatus;
import cn.luwneshacha.thesis.rules.Judgement;
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

    assertEquals("已执行且无错误", passRow.status());
    assertEquals("已执行并发现问题", failRow.status());
    assertEquals("证据不足，待确认", reviewRow.status());
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
    assertEquals("不适用", row.status());
  }

  @Test
  void doesNotCallUnknownApplicabilityErrorFree() {
    var unknown = new CheckResult("BODY-LINE", Applicability.UNKNOWN,
        ExecutionStatus.EXECUTED, Judgement.PASS, "1.5 倍行距", "无法确认", "TEST", null);

    var row = row(new RuleCoverageAnalyzer().analyzeWithCatalog(List.of(unknown)), "BODY-LINE");

    assertEquals("适用性未知", row.applicability());
    assertEquals("适用性未知", row.status());
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
