package cn.luwneshacha.thesis.report;

import cn.luwneshacha.thesis.inspection.InspectionReport;
import cn.luwneshacha.thesis.inspection.CoverageKind;
import cn.luwneshacha.thesis.inspection.UncheckedRegion;
import cn.luwneshacha.thesis.reporting.AnnotationItem;
import cn.luwneshacha.thesis.reporting.AnnotationFindings;
import cn.luwneshacha.thesis.reporting.AnnotationReport;
import cn.luwneshacha.thesis.reporting.AnnotationStatus;
import cn.luwneshacha.thesis.rules.CheckResult;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.docx4j.jaxb.Context;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.BooleanDefaultTrue;
import org.docx4j.wml.CTTblLayoutType;
import org.docx4j.wml.HpsMeasure;
import org.docx4j.wml.ObjectFactory;
import org.docx4j.wml.P;
import org.docx4j.wml.PPr;
import org.docx4j.wml.PPrBase.Spacing;
import org.docx4j.wml.RFonts;
import org.docx4j.wml.STTblLayoutType;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.TblGrid;
import org.docx4j.wml.TblGridCol;
import org.docx4j.wml.TblPr;
import org.docx4j.wml.TblWidth;
import org.docx4j.wml.Tc;
import org.docx4j.wml.TcMar;
import org.docx4j.wml.TcPr;
import org.docx4j.wml.Tr;

/** Generates a Word report that includes rule findings and annotation write status. */
public final class WordReportWriter {
  private final ObjectFactory factory = Context.getWmlObjectFactory();

  public void write(InspectionReport report, Path target) throws IOException { write(report, target, null); }

  public void write(InspectionReport report, Path target, AnnotationReport annotationReport) throws IOException {
    Path temp = target.resolveSibling(target.getFileName() + ".tmp");
    try {
      var annotations = AnnotationFindings.select(report.results()).match(annotationReport);
      WordprocessingMLPackage pkg = WordprocessingMLPackage.createPackage();
      var body = pkg.getMainDocumentPart().getContent();
      body.add(paragraph("\u8bba\u6587\u683c\u5f0f\u68c0\u67e5\u62a5\u544a", true));
      body.add(paragraph("\u68c0\u67e5\u7f16\u53f7\uff1a" + target.getParent().getFileName(), false));
      long passed = report.results().stream().filter(r -> "PASS".equals(r.judgement().name())).count();
      long failures = report.results().stream().filter(r -> "FAIL".equals(r.judgement().name())).count();
      long reviews = report.results().stream().filter(r -> "REVIEW".equals(r.judgement().name())).count();
      body.add(paragraph("\u786e\u5b9a\u68c0\u67e5\u9879\uff1a" + (passed + failures) + " \u9879\uff1b\u901a\u8fc7\uff1a" + passed + " \u9879\uff1b\u9519\u8bef\uff1a" + failures + " \u9879", false));
      body.add(paragraph("\u6682\u65e0\u6cd5\u786e\u8ba4\uff1a" + reviews + " \u9879\uff08\u4e0d\u8ba1\u5165\u9519\u8bef\uff09", false));
      body.add(paragraph("\u9875\u8fb9\u8ddd\u6309\u5b66\u6821\u8981\u6c42\u4ee5 2.6 cm \u4e3a\u51c6\u3002", false));
      Tbl table = factory.createTbl(); configureTable(table);
      String[] headers = {"\u5e8f\u53f7", "\u9519\u8bef\u7c7b\u578b", "\u95ee\u9898\u8bf4\u660e", "\u89c4\u8303\u8981\u6c42", "\u68c0\u6d4b\u7ed3\u679c", "\u4fee\u6539\u5efa\u8bae", "\u6279\u6ce8\u72b6\u6001", "\u672a\u5199\u5165\u539f\u56e0"};
      table.getContent().add(row(headers, true));
      int index = 1;
      for (CheckResult result : report.results()) {
        if (!"FAIL".equals(result.judgement().name())) continue;
        AnnotationItem annotation = annotations.get(result);
        table.getContent().add(row(new String[]{String.valueOf(index++), RuleDescriptions.name(result), RuleDescriptions.reason(result), RuleDescriptions.expectedValue(result), RuleDescriptions.actualValue(result), RuleDescriptions.suggestion(result), annotationStatus(annotationReport, annotation), annotationReason(annotation)}, false));
      }
      if (index == 1) table.getContent().add(row(new String[]{"", "", "\u672a\u53d1\u73b0\u53ef\u786e\u8ba4\u7684\u683c\u5f0f\u9519\u8bef", "", "", "", "", ""}, false));
      body.add(table);
      if (reviews > 0) {
        body.add(paragraph("\u6682\u65e0\u6cd5\u786e\u8ba4\u7684\u68c0\u67e5\u9879", true));
        Tbl reviewTable = factory.createTbl(); configureTable(reviewTable);
        reviewTable.getContent().add(row(new String[]{"\u5e8f\u53f7", "\u68c0\u67e5\u9879", "\u8bf4\u660e", "\u89c4\u8303\u8981\u6c42", "\u8bfb\u53d6\u60c5\u51b5", "\u5efa\u8bae", "\u6279\u6ce8\u72b6\u6001", "\u672a\u5199\u5165\u539f\u56e0"}, true));
        int reviewIndex = 1;
        for (CheckResult result : report.results()) {
          if (!"REVIEW".equals(result.judgement().name())) continue;
          reviewTable.getContent().add(row(new String[]{String.valueOf(reviewIndex++), RuleDescriptions.name(result),
              RuleDescriptions.reason(result), RuleDescriptions.expectedValue(result), RuleDescriptions.actualValue(result),
              RuleDescriptions.suggestion(result), "\u4e0d\u5199\u5165\u9519\u8bef\u6279\u6ce8", ""}, false));
        }
        body.add(reviewTable);
      }
      int[] executionWidths = {900, 1000, 1500, 1200, 1000, 1250, 1000, 1150};
      body.add(paragraph("规则覆盖清单（规则执行情况）", true));
      Tbl executionTable = factory.createTbl(); configureCoverageTable(executionTable, executionWidths);
      executionTable.getContent().add(row(new String[]{"模块", "规则编号", "适用范围", "适用性", "执行状态",
          "判定结论", "数量（总/通过/错误/待确认）", "本次检查要求"}, true, executionWidths));
      if (report.ruleCoverage().isEmpty()) {
        executionTable.getContent().add(row(new String[]{"", "", "本次没有规则执行记录", "", "", "", "", ""},
            false, executionWidths));
      } else {
        for (var coverage : report.ruleCoverage()) {
          executionTable.getContent().add(row(new String[]{coverage.module(), coverage.ruleId(), coverage.scope(),
              coverage.applicability(), coverage.executionStatus(), coverage.status(),
              coverage.total() + "/" + coverage.passed() + "/" + coverage.failed() + "/" + coverage.review(),
              coverage.evidence()}, false, executionWidths));
        }
      }
      body.add(executionTable);

      int[] inventoryWidths = {900, 1000, 1800, 1500, 800, 1900, 1100};
      body.add(paragraph("规则目录、依据与限制", true));
      Tbl inventoryTable = factory.createTbl(); configureCoverageTable(inventoryTable, inventoryWidths);
      inventoryTable.getContent().add(row(new String[]{"模块", "规则编号", "检查内容", "依据来源", "批注支持",
          "检查限制", "批注限制"}, true, inventoryWidths));
      if (report.ruleCoverage().isEmpty()) {
        inventoryTable.getContent().add(row(new String[]{"", "", "本次没有规则目录记录", "", "", "", ""},
            false, inventoryWidths));
      } else {
        for (var coverage : report.ruleCoverage()) {
          inventoryTable.getContent().add(row(new String[]{coverage.module(), coverage.ruleId(), coverage.ruleDescription(),
              coverage.evidenceSource(), annotationSupportLabel(coverage),
              coverage.inspectionLimitation(), coverage.annotationLimitation()}, false, inventoryWidths));
        }
      }
      body.add(inventoryTable);
      addAnnotationCoverageTable(body, report, annotationReport, annotations);
      addUncheckedRegionTables(body, report.uncheckedRegions());
      Files.createDirectories(target.getParent()); pkg.save(temp.toFile());
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception error) {
      try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
      if (error instanceof IOException io) throw io;
      throw new IOException("cannot generate Word report", error);
    }
  }

  private void addUncheckedRegionTables(java.util.List<Object> body,
      java.util.List<UncheckedRegion> regions) {
    body.add(paragraph("未检查区域清单", true));
    Tbl uncheckedTable = factory.createTbl(); configureTable(uncheckedTable);
    uncheckedTable.getContent().add(row(new String[]{"文档部件", "区域", "覆盖类别", "原因", "处理建议", "", "", ""}, true));
    var inspectedGaps = regions.stream()
        .filter(region -> region.kind() != CoverageKind.NOT_ANNOTATABLE)
        .toList();
    if (inspectedGaps.isEmpty()) {
      uncheckedTable.getContent().add(row(new String[]{"", "未检出已识别但未覆盖的区域", "", "", "", "", "", ""}, false));
    } else {
      for (var region : inspectedGaps) {
        uncheckedTable.getContent().add(row(new String[]{region.partName(), region.region(),
            region.kind().label(), region.reason(), region.suggestion(), "", "", ""}, false));
      }
    }
    body.add(uncheckedTable);

    var annotationGaps = regions.stream()
        .filter(region -> region.kind() == CoverageKind.NOT_ANNOTATABLE)
        .toList();
    if (annotationGaps.isEmpty()) return;
    body.add(paragraph("无法写入 Word 批注的区域", true));
    Tbl annotationTable = factory.createTbl(); configureTable(annotationTable);
    annotationTable.getContent().add(row(new String[]{"文档部件", "区域", "覆盖类别", "原因", "处理建议", "", "", ""}, true));
    for (var region : annotationGaps) {
      annotationTable.getContent().add(row(new String[]{region.partName(), region.region(),
          region.kind().label(), region.reason(), region.suggestion(), "", "", ""}, false));
    }
    body.add(annotationTable);
  }

  private String annotationSupportLabel(cn.luwneshacha.thesis.inspection.RuleCoverage coverage) {
    if ("未登记规则".equals(coverage.module())) return "待确认（目录未登记）";
    if (!coverage.annotationSupported()) return "不支持";
    return coverage.annotationLimitation().isBlank() ? "支持" : "部分支持";
  }

  private void addAnnotationCoverageTable(java.util.List<Object> body,
      InspectionReport report,
      AnnotationReport annotationReport,
      java.util.Map<CheckResult, AnnotationItem> annotations) {
    body.add(paragraph("本次 Word 批注写入情况", true));
    if (annotationReport == null) {
      body.add(paragraph("本次未请求写入 Word 批注。", false));
      return;
    }
    int[] widths = {1200, 1300, 1200, 1300, 1400, 1300, 1300};
    Tbl table = factory.createTbl(); configureCoverageTable(table, widths);
    table.getContent().add(row(new String[]{"模块", "规则编号", "问题数", "已写入", "无法定位", "写入失败", "未生成记录"}, true, widths));
    // 按每个问题关联到的批注结果统计覆盖数；同一批注可以覆盖合并的多个文字范围。
    var byRule = new java.util.LinkedHashMap<String, long[]>();
    for (CheckResult result : report.results()) {
      if (!"FAIL".equals(result.judgement().name())) continue;
      long[] counts = byRule.computeIfAbsent(result.ruleId(), ignored -> new long[4]);
      counts[0]++;
      AnnotationItem item = annotations.get(result);
      if (item == null) continue;
      switch (item.status()) {
        case WRITTEN -> counts[1]++;
        case UNSUPPORTED_ANCHOR -> counts[2]++;
        case REJECTED -> counts[3]++;
      }
    }
    if (byRule.isEmpty()) {
      table.getContent().add(row(new String[]{"", "", "0", "0", "0", "0", "0"}, false, widths));
    } else {
      for (var entry : byRule.entrySet()) {
        long[] counts = entry.getValue();
        String module = report.ruleCoverage().stream().filter(rule -> rule.ruleId().equals(entry.getKey()))
            .map(cn.luwneshacha.thesis.inspection.RuleCoverage::module).findFirst().orElse("未登记规则");
        table.getContent().add(row(new String[]{module, entry.getKey(), String.valueOf(counts[0]),
            String.valueOf(counts[1]), String.valueOf(counts[2]), String.valueOf(counts[3]),
            String.valueOf(Math.max(0, counts[0] - counts[1] - counts[2] - counts[3]))}, false, widths));
      }
    }
    body.add(table);
  }

  private static String annotationStatus(AnnotationReport report, AnnotationItem item) {
    if (report == null) return "\u672a\u8bf7\u6c42\u6279\u6ce8";
    if (item == null) return "\u672a\u751f\u6210\u6279\u6ce8\u8bb0\u5f55";
    return switch (item.status()) {
      case WRITTEN -> "PLACED_AT_PAGE_START".equals(item.reason()) ? "已写入 Word 批注（页首）"
          : "PLACED_AT_SOURCE_POINT".equals(item.reason()) ? "已写入 Word 批注（原空白位置）"
          : "\u5df2\u5199\u5165 Word \u6279\u6ce8";
      case UNSUPPORTED_ANCHOR -> "\u672a\u5199\u5165\uff1a\u65e0\u6cd5\u5b9a\u4f4d\u6279\u6ce8\u4f4d\u7f6e";
      case REJECTED -> "\u672a\u5199\u5165\uff1a\u5199\u5165\u5931\u8d25";
    };
  }

  private static String annotationReason(AnnotationItem item) {
    if (item == null || item.status() == AnnotationStatus.WRITTEN) return "";
    return switch (item.reason()) {
      case "SOURCE_HASH_MISMATCH" -> "\u8f93\u5165\u6587\u4ef6\u5728\u68c0\u67e5\u540e\u53d1\u751f\u53d8\u5316";
      case "MISSING_SOURCE" -> "\u68c0\u67e5\u7ed3\u679c\u6ca1\u6709\u5bf9\u5e94\u7684\u6587\u6863\u4f4d\u7f6e\uff0c\u56e0\u6b64\u65e0\u6cd5\u5199\u5165 Word \u6279\u6ce8";
      case "PART_NOT_SUPPORTED" -> "\u95ee\u9898\u4f4d\u4e8e\u5f53\u524d\u6682\u4e0d\u652f\u6301\u6279\u6ce8\u7684\u6587\u6863\u533a\u57df";
      case "EMPTY_ANCHOR" -> "\u5b9a\u4f4d\u5230\u7684\u6bb5\u843d\u6ca1\u6709\u53ef\u6807\u6ce8\u5185\u5bb9";
      case "TABLE_HAS_NO_PARAGRAPH" -> "\u8868\u683c\u4e2d\u6ca1\u6709\u53ef\u653e\u7f6e\u6279\u6ce8\u7684\u6587\u5b57\u6bb5\u843d";
      case "ANCHOR_NOT_PARAGRAPH" -> "\u95ee\u9898\u4f4d\u7f6e\u4e0d\u662f\u53ef\u76f4\u63a5\u6807\u6ce8\u7684\u6587\u5b57\u6bb5\u843d";
      case "PATH_OUT_OF_RANGE" -> "\u95ee\u9898\u4f4d\u7f6e\u5728\u6587\u6863\u4e2d\u5df2\u4e0d\u5b58\u5728";
      case "SOURCE_RANGE_OUT_OF_BOUNDS" -> "检测到的问题范围超出了可标注的文字位置，未能把批注写入对应内容；问题与修改建议仍列在本报告中。";
      case "WRITE_FAILED" -> "\u4fdd\u5b58\u6279\u6ce8\u6587\u4ef6\u65f6\u53d1\u751f\u9519\u8bef";
      default -> item.reason() == null || item.reason().isBlank() ? "\u672a\u63d0\u4f9b\u5177\u4f53\u539f\u56e0" : item.reason();
    };
  }

  private P paragraph(String value, boolean bold) {
    P p = factory.createP(); PPr ppr = factory.createPPr(); Spacing spacing = factory.createPPrBaseSpacing();
    spacing.setBefore(BigInteger.ZERO); spacing.setAfter(BigInteger.ZERO); spacing.setLine(BigInteger.valueOf(280)); spacing.setLineRule(org.docx4j.wml.STLineSpacingRule.AUTO); ppr.setSpacing(spacing); p.setPPr(ppr);
    var r = factory.createR(); var t = factory.createText(); t.setValue(value); t.setSpace("preserve"); r.getContent().add(t); var rpr = factory.createRPr(); RFonts fonts = factory.createRFonts(); fonts.setEastAsia("\u5b8b\u4f53"); fonts.setAscii("Times New Roman"); fonts.setHAnsi("Times New Roman"); rpr.setRFonts(fonts);
    HpsMeasure size = new HpsMeasure(); size.setVal(BigInteger.valueOf(22)); rpr.setSz(size); rpr.setSzCs(size); if (bold) rpr.setB(new BooleanDefaultTrue()); r.setRPr(rpr); p.getContent().add(r); return p;
  }

  private Tr row(String[] values, boolean header) {
    Tr tr = factory.createTr(); if (header) { var trPr = factory.createTrPr(); trPr.getCnfStyleOrDivIdOrGridBefore().add(factory.createCTTrPrBaseTblHeader(new BooleanDefaultTrue())); tr.setTrPr(trPr); }
    int[] widths = values.length == 12
        ? new int[]{500, 700, 850, 700, 700, 400, 400, 400, 500, 1300, 700, 1450}
        : new int[]{350, 950, 1300, 1100, 950, 1300, 1300, 1750};
    return row(values, header, widths);
  }

  private Tr row(String[] values, boolean header, int[] widths) {
    if (values.length != widths.length) throw new IllegalArgumentException("table row width does not match its grid");
    Tr tr = factory.createTr(); if (header) { var trPr = factory.createTrPr(); trPr.getCnfStyleOrDivIdOrGridBefore().add(factory.createCTTrPrBaseTblHeader(new BooleanDefaultTrue())); tr.setTrPr(trPr); }
    for (int i = 0; i < values.length; i++) { Tc tc = factory.createTc(); TcPr tcPr = factory.createTcPr(); TblWidth width = factory.createTblWidth(); width.setW(BigInteger.valueOf(widths[Math.min(i, widths.length - 1)])); width.setType(TblWidth.TYPE_DXA); tcPr.setTcW(width); TcMar margins = factory.createTcMar(); margins.setTop(cellMargin(70)); margins.setBottom(cellMargin(70)); margins.setLeft(cellMargin(60)); margins.setRight(cellMargin(60)); tcPr.setTcMar(margins); tc.setTcPr(tcPr); tc.getContent().add(paragraph(values[i], header)); tr.getContent().add(tc); }
    return tr;
  }

  private void configureTable(Tbl table) {
    TblPr tblPr = factory.createTblPr(); TblWidth width = factory.createTblWidth(); width.setW(BigInteger.valueOf(9000)); width.setType(TblWidth.TYPE_DXA); tblPr.setTblW(width); CTTblLayoutType layout = factory.createCTTblLayoutType(); layout.setType(STTblLayoutType.FIXED); tblPr.setTblLayout(layout); table.setTblPr(tblPr);
    TblGrid grid = factory.createTblGrid(); for (int value : new int[]{350, 950, 1300, 1100, 950, 1300, 1300, 1750}) { TblGridCol col = factory.createTblGridCol(); col.setW(BigInteger.valueOf(value)); grid.getGridCol().add(col); } table.setTblGrid(grid);
  }

  private void configureCoverageTable(Tbl table, int[] columnWidths) {
    TblPr tblPr = factory.createTblPr(); TblWidth width = factory.createTblWidth(); width.setW(BigInteger.valueOf(9000)); width.setType(TblWidth.TYPE_DXA); tblPr.setTblW(width); CTTblLayoutType layout = factory.createCTTblLayoutType(); layout.setType(STTblLayoutType.FIXED); tblPr.setTblLayout(layout); table.setTblPr(tblPr);
    TblGrid grid = factory.createTblGrid(); for (int value : columnWidths) { TblGridCol col = factory.createTblGridCol(); col.setW(BigInteger.valueOf(value)); grid.getGridCol().add(col); } table.setTblGrid(grid);
  }

  private TblWidth cellMargin(long value) { TblWidth margin = factory.createTblWidth(); margin.setW(BigInteger.valueOf(value)); margin.setType(TblWidth.TYPE_DXA); return margin; }
}
