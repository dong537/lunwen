package cn.luwneshacha.thesis.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.inspection.InspectionReport;
import cn.luwneshacha.thesis.reporting.AnnotationItem;
import cn.luwneshacha.thesis.reporting.AnnotationReport;
import cn.luwneshacha.thesis.reporting.AnnotationStatus;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

class WordReportAnnotationMappingTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  @TempDir Path directory;

  @Test
  void mergedCharacterRangesShareTheWrittenAnnotationStatus() throws Exception {
    CheckResult first = finding(49, 50);
    CheckResult merged = finding(50, 55);
    Document document = write(List.of(first, merged), List.of(
        new AnnotationItem(first.ruleId(), AnnotationStatus.WRITTEN, "", first.source())));

    List<List<String>> details = rows((Element) document.getElementsByTagNameNS(W, "tbl").item(0));
    assertEquals(List.of("已写入 Word 批注", "已写入 Word 批注"),
        details.subList(1, details.size()).stream().map(row -> row.get(6)).toList());
  }

  @Test
  void mergedCharacterRangesShareTheActualUnsupportedReason() throws Exception {
    CheckResult first = finding(49, 50);
    CheckResult merged = finding(50, 55);
    Document document = write(List.of(first, merged), List.of(
        new AnnotationItem(first.ruleId(), AnnotationStatus.UNSUPPORTED_ANCHOR,
            "EMPTY_ANCHOR", first.source())));

    List<List<String>> details = rows((Element) document.getElementsByTagNameNS(W, "tbl").item(0));
    assertEquals(List.of("未写入：无法定位批注位置", "未写入：无法定位批注位置"),
        details.subList(1, details.size()).stream().map(row -> row.get(6)).toList());
    assertEquals(List.of("定位到的段落没有可标注内容", "定位到的段落没有可标注内容"),
        details.subList(1, details.size()).stream().map(row -> row.get(7)).toList());
  }

  @ParameterizedTest
  @ValueSource(strings = {"expected", "actual", "reason"})
  void distinctFindingIdentityInTheSameParagraphMustNotBorrowWrittenStatus(String difference) throws Exception {
    CheckResult written = finding(0, 1);
    CheckResult other = new CheckResult(written.ruleId(), written.applicability(), written.executionStatus(),
        written.judgement(), difference.equals("expected") ? "黑体" : written.expected(),
        difference.equals("actual") ? "仿宋" : written.actual(),
        difference.equals("reason") ? "OTHER_FONT_MISMATCH" : written.reasonCode(),
        source(2, 3));
    Document document = write(List.of(written, other), List.of(
        new AnnotationItem(written.ruleId(), AnnotationStatus.WRITTEN, "", written.source())));

    List<List<String>> details = rows((Element) document.getElementsByTagNameNS(W, "tbl").item(0));
    assertEquals("已写入 Word 批注", details.get(1).get(6));
    assertEquals("未生成批注记录", details.get(2).get(6));
    assertEquals(List.of("2", "1", "0", "0", "1"), summary(document).subList(2, 7));
  }

  @Test
  void incompleteRecordsAtASharedExactSourceCannotIdentifyWhichFindingWasWritten() throws Exception {
    CheckResult written = finding(0, 1);
    CheckResult missing = new CheckResult(written.ruleId(), written.applicability(), written.executionStatus(),
        written.judgement(), written.expected(), "仿宋", written.reasonCode(), written.source());
    Document document = write(List.of(written, missing), List.of(
        new AnnotationItem(written.ruleId(), AnnotationStatus.WRITTEN, "", written.source())));

    List<List<String>> details = rows((Element) document.getElementsByTagNameNS(W, "tbl").item(0));
    assertEquals("未生成批注记录", details.get(1).get(6));
    assertEquals("未生成批注记录", details.get(2).get(6));
    assertEquals(List.of("2", "0", "0", "0", "2"), summary(document).subList(2, 7));
  }

  @Test
  void conflictingRecordsAtASharedExactSourceDoNotAssignAnArbitraryOutcome() throws Exception {
    CheckResult written = finding(0, 1);
    CheckResult rejected = new CheckResult(written.ruleId(), written.applicability(), written.executionStatus(),
        written.judgement(), written.expected(), "仿宋", written.reasonCode(), written.source());
    Document document = write(List.of(written, rejected), List.of(
        new AnnotationItem(written.ruleId(), AnnotationStatus.WRITTEN, "", written.source()),
        new AnnotationItem(rejected.ruleId(), AnnotationStatus.REJECTED, "WRITE_FAILED", rejected.source())));

    List<List<String>> details = rows((Element) document.getElementsByTagNameNS(W, "tbl").item(0));
    assertEquals("未生成批注记录", details.get(1).get(6));
    assertEquals("未生成批注记录", details.get(2).get(6));
    assertEquals(List.of("2", "0", "0", "0", "2"), summary(document).subList(2, 7));
  }

  @Test
  void completeConsistentRecordsAtASharedExactSourceKeepBothWrittenStatuses() throws Exception {
    CheckResult first = finding(0, 1);
    CheckResult second = new CheckResult(first.ruleId(), first.applicability(), first.executionStatus(),
        first.judgement(), first.expected(), "仿宋", first.reasonCode(), first.source());
    AnnotationItem written = new AnnotationItem(first.ruleId(), AnnotationStatus.WRITTEN, "", first.source());
    Document document = write(List.of(first, second), List.of(written, written));

    List<List<String>> details = rows((Element) document.getElementsByTagNameNS(W, "tbl").item(0));
    assertEquals("已写入 Word 批注", details.get(1).get(6));
    assertEquals("已写入 Word 批注", details.get(2).get(6));
    assertEquals(List.of("2", "2", "0", "0", "0"), summary(document).subList(2, 7));
  }

  @Test
  void explicitRangeFailureIsNotOverwrittenByTheRepresentativeSuccess() throws Exception {
    CheckResult first = finding(49, 50);
    CheckResult second = finding(50, 55);
    Document document = write(List.of(first, second), List.of(
        new AnnotationItem(first.ruleId(), AnnotationStatus.WRITTEN, "", first.source()),
        new AnnotationItem(second.ruleId(), AnnotationStatus.UNSUPPORTED_ANCHOR,
            "SOURCE_RANGE_OUT_OF_BOUNDS", second.source())));

    List<List<String>> details = rows((Element) document.getElementsByTagNameNS(W, "tbl").item(0));
    assertEquals("已写入 Word 批注", details.get(1).get(6));
    assertEquals("未写入：无法定位批注位置", details.get(2).get(6));
    assertEquals("检测到的问题范围超出了可标注的文字位置，未能把批注写入对应内容；问题与修改建议仍列在本报告中。",
        details.get(2).get(7));
    assertEquals(List.of("2", "1", "1", "0", "0"), summary(document).subList(2, 7));
  }

  @Test
  void annotationSummaryCountsAllFindingsCoveredByTheMergedAnnotation() throws Exception {
    CheckResult first = finding(49, 50);
    Document document = write(List.of(first, finding(50, 55)), List.of(
        new AnnotationItem(first.ruleId(), AnnotationStatus.WRITTEN, "", first.source())));

    assertEquals(List.of("2", "2", "0", "0", "0"), summary(document).subList(2, 7));
  }

  @Test
  void annotationSummaryPreservesRejectedCoverageForMergedFindings() throws Exception {
    CheckResult first = finding(49, 50);
    Document document = write(List.of(first, finding(50, 55)), List.of(
        new AnnotationItem(first.ruleId(), AnnotationStatus.REJECTED, "WRITE_FAILED", first.source())));

    assertEquals(List.of("2", "0", "0", "2", "0"), summary(document).subList(2, 7));
  }

  @Test
  void outOfBoundsAnnotationFailureHasAnActionableChineseReason() throws Exception {
    CheckResult first = finding(49, 50);
    Document document = write(List.of(first), List.of(
        new AnnotationItem(first.ruleId(), AnnotationStatus.UNSUPPORTED_ANCHOR,
            "SOURCE_RANGE_OUT_OF_BOUNDS", first.source())));

    List<List<String>> details = rows((Element) document.getElementsByTagNameNS(W, "tbl").item(0));
    assertEquals("未写入：无法定位批注位置", details.get(1).get(6));
    assertEquals("检测到的问题范围超出了可标注的文字位置，未能把批注写入对应内容；问题与修改建议仍列在本报告中。",
        details.get(1).get(7));
  }

  @Test
  void genuinelyMissingAnnotationRemainsMissingForEveryMergedFinding() throws Exception {
    Document document = write(List.of(finding(49, 50), finding(50, 55)), List.of());

    List<List<String>> details = rows((Element) document.getElementsByTagNameNS(W, "tbl").item(0));
    assertEquals(List.of("未生成批注记录", "未生成批注记录"),
        details.subList(1, details.size()).stream().map(row -> row.get(6)).toList());
    assertEquals(List.of("2", "0", "0", "0", "2"), summary(document).subList(2, 7));
  }

  private Document write(List<CheckResult> findings, List<AnnotationItem> annotations) throws Exception {
    Path target = directory.resolve("report.docx");
    new WordReportWriter().write(new InspectionReport("hash", "school-v1", findings, List.of()),
        target, new AnnotationReport(annotations, true));
    try (ZipFile zip = new ZipFile(target.toFile());
        var input = zip.getInputStream(zip.getEntry("word/document.xml"))) {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      return factory.newDocumentBuilder().parse(input);
    }
  }

  private static CheckResult finding(int start, int end) {
    return new CheckResult("BODY-FONT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "宋体", "楷体", "FONT_MISMATCH", source(start, end));
  }

  private static SourceAddress source(int start, int end) {
    return new SourceAddress("word/document.xml", List.of(0, 196), start, end);
  }

  private static List<String> summary(Document document) {
    var tables = document.getElementsByTagNameNS(W, "tbl");
    for (int index = 0; index < tables.getLength(); index++) {
      var tableRows = rows((Element) tables.item(index));
      if (tableRows.get(0).contains("未生成记录")) {
        return tableRows.stream().filter(row -> row.contains("BODY-FONT")).findFirst().orElseThrow();
      }
    }
    throw new AssertionError("批注覆盖汇总表缺失");
  }

  private static List<List<String>> rows(Element table) {
    List<List<String>> result = new ArrayList<>();
    var rows = table.getElementsByTagNameNS(W, "tr");
    for (int rowIndex = 0; rowIndex < rows.getLength(); rowIndex++) {
      var cells = ((Element) rows.item(rowIndex)).getElementsByTagNameNS(W, "tc");
      List<String> values = new ArrayList<>();
      for (int cellIndex = 0; cellIndex < cells.getLength(); cellIndex++) {
        values.add(cells.item(cellIndex).getTextContent().trim());
      }
      result.add(values);
    }
    return result;
  }
}
