package cn.luwneshacha.thesis.report;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.inspection.InspectionReport;
import cn.luwneshacha.thesis.inspection.RuleCoverage;
import cn.luwneshacha.thesis.inspection.UncheckedRegion;
import cn.luwneshacha.thesis.reporting.AnnotationItem;
import cn.luwneshacha.thesis.reporting.AnnotationReport;
import cn.luwneshacha.thesis.reporting.AnnotationStatus;
import cn.luwneshacha.thesis.rules.Applicability;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.ExecutionStatus;
import cn.luwneshacha.thesis.rules.Judgement;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

class WordReportWriterTest {
  @Test
  void includesRuleCoverageAndUninspectedRegionReason() throws Exception {
    Path target = Files.createTempFile("thesis-report-coverage-", ".docx");
    var report = new InspectionReport("hash", "school-v1", List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(),
        List.of(new RuleCoverage("正文格式", "BODY-LINE", "EXECUTED", 12, 12, 0, 0, "1.5 倍行距")),
        List.of(new UncheckedRegion("word/header1.xml", "页眉文字", "当前版本未纳入页眉格式检查")));

    new WordReportWriter().write(report, target);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(document.contains("规则覆盖清单"));
      assertTrue(document.contains("BODY-LINE"));
      assertTrue(document.contains("未检查区域清单"));
      assertTrue(document.contains("word/header1.xml"));
      assertTrue(document.contains("当前版本未纳入页眉格式检查"));
      assertTrue(document.contains("覆盖类别"));
      assertTrue(document.contains("处理建议"));
    } finally {
      Files.deleteIfExists(target);
    }
  }

  @Test
  void reportsUnregisteredRuleMetadataAndUnknownAnnotationCapability() throws Exception {
    Path target = Files.createTempFile("thesis-report-unregistered-rule-", ".docx");
    var coverage = new cn.luwneshacha.thesis.inspection.RuleCoverageAnalyzer()
        .analyzeWithCatalog(List.of(new CheckResult("NEW-RULE", Applicability.APPLICABLE,
            ExecutionStatus.EXECUTED, Judgement.FAIL, "expected", "actual", "TEST", null)));
    var report = new InspectionReport("hash", "school-v1", List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), coverage, List.of());

    new WordReportWriter().write(report, target);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(document.contains("目录未登记"));
      assertTrue(document.contains("待确认（目录未登记）"));
      assertTrue(document.contains("请确认编号有效"));
      assertTrue(document.contains("规则依据尚未登记"));
      assertTrue(document.contains("检查边界和规范依据尚未核实"));
    } finally {
      Files.deleteIfExists(target);
    }
  }

  @Test
  void reportsPartialAnnotationSupportWhenCatalogListsAnchorLimits() throws Exception {
    Path target = Files.createTempFile("thesis-report-partial-annotation-", ".docx");
    var coverage = new cn.luwneshacha.thesis.inspection.RuleCoverageAnalyzer()
        .analyzeWithCatalog(List.of()).stream()
        .filter(row -> row.ruleId().equals("EQ-03"))
        .findFirst().orElseThrow();
    var report = new InspectionReport("hash", "school-v1", List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(coverage), List.of());

    new WordReportWriter().write(report, target);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(document.contains("部分支持"));
    } finally {
      Files.deleteIfExists(target);
    }
  }

  @Test
  void separatesCoverageExecutionFromCatalogEvidenceAndKeepsTableGridsConsistent() throws Exception {
    Path target = Files.createTempFile("thesis-report-rule-catalog-", ".docx");
    var coverage = new RuleCoverage("公式", "EQ-03", "证据不足，待确认", 1, 0, 0, 1,
        "未读取到公式编号的独立右对齐设置", "OMML 与公式编号段落", "适用", "已执行", false,
        "公式对象没有可用批注锚点", "公式编号位置与对齐方式", "OMML / 公式编号 XML",
        "复杂公式对象的内部属性无法从当前结构确认");
    var report = new InspectionReport("hash", "school-v1", List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(coverage), List.of());

    new WordReportWriter().write(report, target);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      var documentXml = zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes();
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      var document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(documentXml));
      var tables = document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "tbl");
      boolean executionTableFound = false;
      boolean catalogTableFound = false;
      for (int tableIndex = 0; tableIndex < tables.getLength(); tableIndex++) {
        var table = (org.w3c.dom.Element) tables.item(tableIndex);
        var gridColumns = table.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "gridCol");
        var rows = table.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "tr");
        assertTrue(gridColumns.getLength() > 0);
        for (int rowIndex = 0; rowIndex < rows.getLength(); rowIndex++) {
          var cells = ((org.w3c.dom.Element) rows.item(rowIndex)).getElementsByTagNameNS(
              "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "tc");
          assertTrue(cells.getLength() == gridColumns.getLength(),
              "table grid and row must declare the same number of columns");
        }
        var text = table.getTextContent();
        executionTableFound |= text.contains("数量（总/通过/错误/待确认）") && text.contains("本次检查要求")
            && text.contains("1/0/0/1") && text.contains(coverage.evidence())
            && !text.contains(coverage.evidenceSource());
        catalogTableFound |= text.contains("依据来源") && text.contains("检查限制") && text.contains("批注限制")
            && text.contains(coverage.evidenceSource()) && !text.contains(coverage.evidence())
            && text.contains("公式对象没有可用批注锚点")
            && text.contains("复杂公式对象的内部属性无法从当前结构确认");
      }
      assertTrue(executionTableFound, "execution results must have their own compact table");
      assertTrue(catalogTableFound, "catalog evidence and independent limitations must be visible");
    } finally {
      Files.deleteIfExists(target);
    }
  }

  @Test
  void includesAnnotationStatusAndFailureReason() throws Exception {
    Path target = Files.createTempFile("thesis-report-", ".docx");
    SourceAddress source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 1);
    CheckResult finding = new CheckResult("BODY-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "expected", "actual", "FONT_MISMATCH", source);
    InspectionReport report = new InspectionReport("hash", "school-v1", List.of(finding), List.of());
    AnnotationReport annotations = new AnnotationReport(List.of(
        new AnnotationItem("BODY-01", AnnotationStatus.UNSUPPORTED_ANCHOR, "PART_NOT_SUPPORTED", source)), false);

    new WordReportWriter().write(report, target, annotations);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(document.contains("\u672a\u5199\u5165"));
      assertTrue(document.contains("\u95ee\u9898\u4f4d\u4e8e\u5f53\u524d\u6682\u4e0d\u652f\u6301\u6279\u6ce8\u7684\u6587\u6863\u533a\u57df"));
      assertTrue(document.contains("本次 Word 批注写入情况"));
      assertTrue(document.contains("无法定位"));
    } finally {
      Files.deleteIfExists(target);
    }
  }

  @Test
  void reportsPerRuleAnnotationWriteOutcomesSeparatelyFromCapability() throws Exception {
    Path target = Files.createTempFile("thesis-report-annotation-coverage-", ".docx");
    SourceAddress writtenSource = new SourceAddress("word/document.xml", List.of(0), 0, 1);
    SourceAddress rejectedSource = new SourceAddress("word/document.xml", List.of(1), 0, 1);
    CheckResult writtenFinding = new CheckResult("BODY-01", Applicability.APPLICABLE,
        ExecutionStatus.EXECUTED, Judgement.FAIL, "expected", "actual", "FONT_MISMATCH", writtenSource);
    CheckResult rejectedFinding = new CheckResult("BODY-02", Applicability.APPLICABLE,
        ExecutionStatus.EXECUTED, Judgement.FAIL, "expected", "actual", "FONT_MISMATCH", rejectedSource);
    var annotations = new AnnotationReport(List.of(
        new AnnotationItem("BODY-01", AnnotationStatus.WRITTEN, "", writtenSource),
        new AnnotationItem("BODY-02", AnnotationStatus.REJECTED, "WRITE_FAILED", rejectedSource)), true);

    new WordReportWriter().write(new InspectionReport("hash", "school-v1",
        List.of(writtenFinding, rejectedFinding), List.of()), target, annotations);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(document.contains("BODY-01"));
      assertTrue(document.contains("BODY-02"));
      assertTrue(document.contains("已写入"));
      assertTrue(document.contains("写入失败"));
    } finally {
      Files.deleteIfExists(target);
    }
  }

  @Test
  void listsFailedRulesEvenWhenNoAnnotationRecordWasProduced() throws Exception {
    Path target = Files.createTempFile("thesis-report-missing-annotation-", ".docx");
    SourceAddress source = new SourceAddress("word/document.xml", List.of(0), 0, 1);
    CheckResult finding = new CheckResult("BODY-FONT", Applicability.APPLICABLE,
        ExecutionStatus.EXECUTED, Judgement.FAIL, "宋体", "其他字体", "FONT_MISMATCH", source);
    var coverage = new RuleCoverage("正文格式", "BODY-FONT", "FAIL", 1, 0, 1, 0, "字体检查",
        "正文", "适用", "已执行", true, "");
    var report = new InspectionReport("hash", "school-v1", List.of(finding), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(coverage), List.of());
    var annotations = new AnnotationReport(List.of(), false);

    new WordReportWriter().write(report, target, annotations);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      var documentXml = zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes();
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      var document = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(documentXml));
      var tables = document.getElementsByTagNameNS(
          "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "tbl");
      boolean foundSummary = false;
      for (int tableIndex = 0; tableIndex < tables.getLength(); tableIndex++) {
        var table = (org.w3c.dom.Element) tables.item(tableIndex);
        if (!table.getTextContent().contains("问题数")
            || !table.getTextContent().contains("已写入")
            || !table.getTextContent().contains("未生成记录")
            || !table.getTextContent().contains("BODY-FONT")) continue;
        var rows = table.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "tr");
        for (int rowIndex = 0; rowIndex < rows.getLength(); rowIndex++) {
          var row = (org.w3c.dom.Element) rows.item(rowIndex);
          if (!row.getTextContent().contains("BODY-FONT")) continue;
          var cells = row.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "tc");
          String[] values = new String[cells.getLength()];
          for (int cellIndex = 0; cellIndex < cells.getLength(); cellIndex++) {
            values[cellIndex] = cells.item(cellIndex).getTextContent().trim();
          }
          assertTrue(java.util.Arrays.equals(values,
              new String[]{"正文格式", "BODY-FONT", "1", "0", "0", "0", "1"}));
          foundSummary = true;
        }
      }
      assertTrue(foundSummary, "a failed rule without an annotation item must remain visible in the summary");
    } finally {
      Files.deleteIfExists(target);
    }
  }

  @Test
  void reportsUndeterminedFindingsSeparatelyWithoutCountingThemAsErrors() throws Exception {
    Path target = Files.createTempFile("thesis-report-review-", ".docx");
    SourceAddress source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 1);
    CheckResult failure = new CheckResult("EQ-07", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "变量含义和单位说明", "x", "EQUATION_VARIABLES_MISSING", source);
    CheckResult review = new CheckResult("EQ-03", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.REVIEW, "equation centered; number right aligned", "未读取到明确设置",
        "EQUATION_ALIGNMENT_UNKNOWN", source);

    new WordReportWriter().write(new InspectionReport("hash", "school-v1", List.of(failure, review), List.of()), target);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(document.contains("错误：1 项"));
      assertTrue(document.contains("暂无法确认：1 项"));
      assertTrue(document.contains("公式或编号的对齐方式无法从文件中确认"));
      assertTrue(document.contains("不代表格式错误"));
      assertTrue(!document.contains("按公式对齐错误处理"));
    } finally {
      Files.deleteIfExists(target);
    }
  }

  @Test
  void reportKeepsLayoutAndCitationReviewsOutOfTheErrorCount() throws Exception {
    Path target = Files.createTempFile("thesis-report-layout-review-", ".docx");
    SourceAddress source = new SourceAddress("word/document.xml", List.of(0, 1), 0, 1);
    List<CheckResult> reviews = List.of(
        new CheckResult("CITE-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.REVIEW, "citation format", null, "CITATION_FORMAT_UNKNOWN", source),
        new CheckResult("FIG-02", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.REVIEW, "figure layout", null, "FIGURE_LAYOUT_REVIEW", source),
        new CheckResult("FIG-01", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.REVIEW, "figure reference", null, "FIGURE_REFERENCE_NEEDS_REVIEW", source),
        new CheckResult("TAB-03", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.REVIEW, "table caption location", null, "TABLE_LAYOUT_REVIEW", source),
        new CheckResult("TAB-02", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
            Judgement.REVIEW, "three-line table", null, "TABLE_BORDER_EVIDENCE_UNRESOLVED", source));

    new WordReportWriter().write(new InspectionReport("hash", "school-v1", reviews, List.of()), target);

    try (ZipFile zip = new ZipFile(target.toFile())) {
      String document = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(document.contains("错误：0 项"));
      assertTrue(document.contains("暂无法确认：5 项"));
      assertTrue(document.contains("XML 无法证明图题是否位于图片下方并居中，暂无法确认是否符合规范"));
      assertTrue(document.contains("只有确认缺少时再补充引用"));
      assertTrue(!document.contains("按未引用处理"));
    } finally {
      Files.deleteIfExists(target);
    }
  }
}
