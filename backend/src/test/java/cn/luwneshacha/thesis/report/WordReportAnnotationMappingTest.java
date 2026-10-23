package cn.luwneshacha.thesis.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
import org.junit.jupiter.params.provider.EnumSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

class WordReportAnnotationMappingTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  @TempDir Path directory;

  @ParameterizedTest
  @EnumSource(AnnotationStatus.class)
  void annotationWriteOutcomeDoesNotChangeTheProblemSummary(AnnotationStatus status) throws Exception {
    List<CheckResult> findings = List.of(finding(49, 50), finding(50, 55));
    Document withoutAnnotations = write(findings, null);
    AnnotationReport annotations = new AnnotationReport(List.of(
        new AnnotationItem("BODY-FONT", status, "PRIVATE_ANNOTATION_REASON", findings.getFirst().source())), true);

    Document withAnnotations = write(findings, annotations);

    assertEquals(visibleText(withoutAnnotations), visibleText(withAnnotations));
    assertProblemSummary(withAnnotations, 2);
  }

  @Test
  void missingAnnotationRecordsDoNotRemoveProblemsOrAddCoverageInformation() throws Exception {
    List<CheckResult> findings = List.of(finding(49, 50), finding(50, 55));
    Document withoutAnnotations = write(findings, null);

    Document withMissingRecords = write(findings, new AnnotationReport(List.of(), true));

    assertEquals(visibleText(withoutAnnotations), visibleText(withMissingRecords));
    assertProblemSummary(withMissingRecords, 2);
  }

  @Test
  void unsuccessfulAnnotationOutputDoesNotChangeTheInspectionSummary() throws Exception {
    List<CheckResult> findings = List.of(finding(0, 1));
    AnnotationReport unsuccessful = new AnnotationReport(List.of(
        new AnnotationItem("BODY-FONT", AnnotationStatus.REJECTED, "WRITE_FAILED", findings.getFirst().source())), false);
    Document withoutAnnotations = write(findings, null);

    Document withUnsuccessfulOutput = write(findings, unsuccessful);

    assertEquals(visibleText(withoutAnnotations), visibleText(withUnsuccessfulOutput));
    assertProblemSummary(withUnsuccessfulOutput, 1);
  }

  @Test
  void ordinaryReportGenerationNeedsNoAnnotationRequest() throws Exception {
    List<CheckResult> findings = List.of(finding(0, 1));
    Path target = directory.resolve("ordinary.docx");
    new WordReportWriter().write(new InspectionReport("hash", "school-v1", findings, List.of()), target);

    assertProblemSummary(read(target), 1);
  }

  private static void assertProblemSummary(Document document, int count) {
    var tables = document.getElementsByTagNameNS(W, "tbl");
    assertEquals(1, tables.getLength(), "报告只保留一张汇总表");
    List<List<String>> summary = rows((Element) tables.item(0));
    assertEquals(List.of("检查模块", "中文规范要求", "问题数量"), summary.getFirst());
    assertEquals(2, summary.size(), "同一规则应合并为一行");
    assertEquals(3, summary.get(1).size());
    assertEquals(String.valueOf(count), summary.get(1).get(2));
    String text = visibleText(document);
    for (String omitted : List.of("批注", "已写入", "无法定位", "写入失败", "未生成记录",
        "PRIVATE_ANNOTATION_REASON", "WRITE_FAILED", "BODY-FONT")) {
      assertFalse(text.contains(omitted), "报告不应包含：" + omitted);
    }
  }

  private Document write(List<CheckResult> findings, AnnotationReport annotations) throws Exception {
    Path target = directory.resolve("report.docx");
    new WordReportWriter().write(new InspectionReport("hash", "school-v1", findings, List.of()), target, annotations);
    return read(target);
  }

  private static Document read(Path target) throws Exception {
    try (ZipFile zip = new ZipFile(target.toFile());
        var input = zip.getInputStream(zip.getEntry("word/document.xml"))) {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      return factory.newDocumentBuilder().parse(input);
    }
  }

  private static CheckResult finding(int start, int end) {
    return new CheckResult("BODY-FONT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "宋体", "楷体", "FONT_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 196), start, end));
  }

  private static String visibleText(Document document) {
    StringBuilder text = new StringBuilder();
    var nodes = document.getElementsByTagNameNS(W, "t");
    for (int index = 0; index < nodes.getLength(); index++) {
      text.append(nodes.item(index).getTextContent());
    }
    return text.toString();
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


