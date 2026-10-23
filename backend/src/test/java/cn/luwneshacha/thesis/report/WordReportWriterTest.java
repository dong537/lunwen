package cn.luwneshacha.thesis.report;

import static org.junit.jupiter.api.Assertions.*;

import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.inspection.InspectionReport;
import cn.luwneshacha.thesis.inspection.RuleCatalog;
import cn.luwneshacha.thesis.inspection.RuleCoverage;
import cn.luwneshacha.thesis.inspection.UncheckedRegion;
import cn.luwneshacha.thesis.rules.Applicability;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.ExecutionStatus;
import cn.luwneshacha.thesis.rules.Judgement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

class WordReportWriterTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  @TempDir Path directory;

  @Test
  void keepsOnlyOneThreeColumnSummaryWithoutFindingsOrCoverageDetails() throws Exception {
    CheckResult failure = finding("BODY-LINE", Judgement.FAIL);
    var report = new InspectionReport("private-source-hash", "private-rule-set",
        List.of(failure), List.of("private-diagnostic"),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(new RuleCoverage("正文格式", "BODY-LINE", "EXECUTED", 99, 0, 99, 0, "private-evidence")),
        List.of(new UncheckedRegion("word/header1.xml", "private-region", "private-region-reason")));

    Document document = write(report);
    assertEquals(1, document.getElementsByTagNameNS(W, "tbl").getLength());
    assertEquals(List.of("检查模块", "中文规范要求", "问题数量"), rows(document).getFirst());
    assertEquals(2, rows(document).size());
    String text = document.getDocumentElement().getTextContent();
    assertTrue(text.contains("论文格式检查汇总"));
    assertTrue(text.contains("明确问题总数：1 项"));
    assertTrue(text.contains("待人工确认总数：0 项（不计入问题数量）"));
    assertTrue(text.contains("正文采用 1.5 倍行距"));
    for (String removed : List.of("格式问题明细", "规则覆盖", "规则目录", "未检查区域", "批注",
        "BODY-LINE", "private-", "word/document.xml", "修改建议", "实际值")) {
      assertFalse(text.contains(removed), "报告不应展示：" + removed);
    }
  }

  @Test
  void mergesRepeatedFailuresByRuleRegardlessOfActualValueAndSource() throws Exception {
    CheckResult first = finding("BODY-LINE", Judgement.FAIL);
    CheckResult differentEvidence = new CheckResult(first.ruleId(), first.applicability(), first.executionStatus(),
        first.judgement(), "private-different-expected", "private-different-actual", "private-other-reason",
        new SourceAddress("word/document.xml", List.of(2), 4, 8));
    Document document = write(List.of(first, differentEvidence, finding("BODY-SIZE", Judgement.FAIL)));
    List<List<String>> rows = rows(document);
    assertEquals(3, rows.size());
    assertEquals("正文格式", rows.get(1).get(0));
    assertEquals("2", rows.get(1).get(2));
    assertEquals("1", rows.get(2).get(2));
    assertNotEquals(rows.get(1).get(1), rows.get(2).get(1));
    assertTrue(document.getDocumentElement().getTextContent().contains("明确问题总数：3 项"));
  }

  @Test
  void countsReviewsSeparatelyWithoutAddingThemToFailedRows() throws Exception {
    Document document = write(List.of(finding("BODY-LINE", Judgement.FAIL),
        finding("BODY-LINE", Judgement.REVIEW), finding("BODY-LINE", Judgement.PASS),
        finding("EQ-03", Judgement.REVIEW)));
    assertEquals(2, rows(document).size());
    assertEquals("1", rows(document).get(1).get(2));
    String text = document.getDocumentElement().getTextContent();
    assertTrue(text.contains("明确问题总数：1 项"));
    assertTrue(text.contains("待人工确认总数：2 项（不计入问题数量）"));
    assertFalse(text.contains("公式对齐"));
  }

  @ParameterizedTest
  @EnumSource(value = Judgement.class, names = {"PASS", "REVIEW"})
  void showsNoConfirmedProblemWithoutClaimingTheWholeThesisPassed(Judgement judgement) throws Exception {
    Document document = write(List.of(finding("BODY-LINE", judgement)));
    assertEquals(List.of("—", "未发现可确认的格式问题", "0"), rows(document).get(1));
    String text = document.getDocumentElement().getTextContent();
    assertTrue(text.contains("明确问题总数：0 项"));
    assertTrue(text.contains("待人工确认总数：" + (judgement == Judgement.REVIEW ? 1 : 0) + " 项"));
    assertFalse(text.contains("全部通过"));
    assertFalse(text.contains("全部合格"));
  }

  @Test
  void emptyInspectionDoesNotInventPassedRules() throws Exception {
    Document document = write(List.of());
    assertEquals(List.of("—", "未发现可确认的格式问题", "0"), rows(document).get(1));
    assertTrue(document.getDocumentElement().getTextContent().contains("明确问题总数：0 项"));
    assertFalse(document.getDocumentElement().getTextContent().contains("通过："));
  }

  @Test
  void unknownRuleUsesSafeChineseFallbackWithoutRawEvidence() throws Exception {
    Document document = write(List.of(finding("UNREGISTERED-private-rule", Judgement.FAIL)));
    assertEquals(List.of("其他检查项", "其他格式要求", "1"), rows(document).get(1));
    assertFalse(document.getDocumentElement().getTextContent().contains("private-"));
  }

  @Test
  void doesNotExposeSpecificTableHeadersOrEquationNumbersAsRequirements() throws Exception {
    Document document = write(List.of(
        new CheckResult("TAB-08", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "private-original-header", "private-current-header", "private-reason", null),
        new CheckResult("EQ-04", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.FAIL, "body reference to equation 7-11", "private-formula", "private-reason", null)));
    String text = document.getDocumentElement().getTextContent();
    assertFalse(text.contains("private-"));
    assertFalse(text.contains("7-11"));
    assertFalse(text.contains("body reference"));
    assertEquals(3, rows(document).size());
  }

  @Test
  void retainsEveryRuleWithConsistentColumnWidthsAndRepeatingHeaderForContinuation() throws Exception {
    var failures = RuleCatalog.entries().stream().map(entry -> finding(entry.ruleId(), Judgement.FAIL)).toList();
    Document document = write(failures);
    List<List<String>> rows = rows(document);
    assertEquals(failures.size() + 1, rows.size());
    assertEquals(failures.size(), rows.subList(1, rows.size()).stream()
        .mapToLong(row -> Long.parseLong(row.get(2))).sum());

    Element table = (Element) document.getElementsByTagNameNS(W, "tbl").item(0);
    var gridColumns = table.getElementsByTagNameNS(W, "gridCol");
    assertEquals(3, gridColumns.getLength());
    long width = 0;
    for (int column = 0; column < 3; column++) width += Long.parseLong(((Element) gridColumns.item(column)).getAttributeNS(W, "w"));
    assertEquals(Long.toString(width), ((Element) table.getElementsByTagNameNS(W, "tblW").item(0)).getAttributeNS(W, "w"));
    var xmlRows = table.getElementsByTagNameNS(W, "tr");
    for (int row = 0; row < xmlRows.getLength(); row++) {
      Element xmlRow = (Element) xmlRows.item(row);
      var cellWidths = xmlRow.getElementsByTagNameNS(W, "tcW");
      assertEquals(3, cellWidths.getLength());
      assertEquals(row == 0 ? 1 : 0, xmlRow.getElementsByTagNameNS(W, "tblHeader").getLength());
      for (int column = 0; column < 3; column++) {
        assertEquals(((Element) gridColumns.item(column)).getAttributeNS(W, "w"),
            ((Element) cellWidths.item(column)).getAttributeNS(W, "w"));
      }
    }
    assertEquals(0, document.getElementsByTagNameNS(W, "pageBreakBefore").getLength());
    long pageWidth = Long.parseLong(((Element) document.getElementsByTagNameNS(W, "pgSz").item(0)).getAttributeNS(W, "w"));
    Element margins = (Element) document.getElementsByTagNameNS(W, "pgMar").item(0);
    assertTrue(width <= pageWidth - Long.parseLong(margins.getAttributeNS(W, "left"))
        - Long.parseLong(margins.getAttributeNS(W, "right")));
  }

  private Document write(List<CheckResult> results) throws Exception {
    return write(new InspectionReport("private-source-hash", "private-rule-set", results, List.of()));
  }

  private Document write(InspectionReport report) throws Exception {
    Path target = directory.resolve("report.docx");
    new WordReportWriter().write(report, target);
    try (ZipFile zip = new ZipFile(target.toFile()); var input = zip.getInputStream(zip.getEntry("word/document.xml"))) {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      return factory.newDocumentBuilder().parse(input);
    }
  }

  private static CheckResult finding(String ruleId, Judgement judgement) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED, judgement,
        "private-expected", "private-actual", "private-reason",
        new SourceAddress("word/document.xml", List.of(0, 1), 2, 3));
  }

  private static List<List<String>> rows(Document document) {
    Element table = (Element) document.getElementsByTagNameNS(W, "tbl").item(0);
    List<List<String>> rows = new ArrayList<>();
    var xmlRows = table.getElementsByTagNameNS(W, "tr");
    for (int row = 0; row < xmlRows.getLength(); row++) {
      var cells = ((Element) xmlRows.item(row)).getElementsByTagNameNS(W, "tc");
      List<String> values = new ArrayList<>();
      for (int cell = 0; cell < cells.getLength(); cell++) values.add(cells.item(cell).getTextContent().trim());
      rows.add(values);
    }
    return rows;
  }
}


