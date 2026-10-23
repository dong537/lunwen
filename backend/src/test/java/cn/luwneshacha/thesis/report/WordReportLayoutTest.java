package cn.luwneshacha.thesis.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.inspection.InspectionReport;
import cn.luwneshacha.thesis.inspection.RuleCatalog;
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
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Contract tests for the compact three-column Word summary. */
class WordReportLayoutTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  @TempDir Path directory;

  @Test
  void writesA4PageWithExplicitMarginsAndSummaryHeading() throws Exception {
    Document document = write(List.of(finding("BODY-LINE", Judgement.FAIL)));
    Element section = first(document, "sectPr");
    Element size = first(section, "pgSz");
    assertEquals("11906", size.getAttributeNS(W, "w"));
    assertEquals("16838", size.getAttributeNS(W, "h"));
    Element margins = first(section, "pgMar");
    assertEquals("1440", margins.getAttributeNS(W, "top"));
    assertEquals("1440", margins.getAttributeNS(W, "bottom"));
    assertEquals("1440", margins.getAttributeNS(W, "left"));
    assertEquals("1440", margins.getAttributeNS(W, "right"));
    assertTrue(text(document).contains("论文格式检查汇总"));
    assertTrue(text(document).contains("明确问题总数：1 项"));
    assertTrue(text(document).contains("待人工确认总数：0 项（不计入问题数量）"));
  }

  @Test
  void containsOnlyOneThreeColumnSummaryTable() throws Exception {
    Document document = write(List.of(finding("BODY-LINE", Judgement.FAIL),
        finding("BODY-SIZE", Judgement.FAIL)));
    var tables = document.getElementsByTagNameNS(W, "tbl");
    assertEquals(1, tables.getLength());
    Element table = (Element) tables.item(0);
    assertEquals(3, table.getElementsByTagNameNS(W, "gridCol").getLength());
    List<List<String>> rows = rows(table);
    assertEquals(List.of("检查模块", "中文规范要求", "问题数量"), rows.getFirst());
    assertEquals(3, rows.size());
    assertEquals(2, rows.subList(1, rows.size()).stream()
        .mapToInt(row -> Integer.parseInt(row.get(2))).sum());
    assertTrue(rows.get(1).get(1).contains("1.5 倍行距")
        || rows.get(2).get(1).contains("1.5 倍行距"));
  }

  @Test
  void repeatsHeaderContractAndKeepsFixedGridWithinPageWidth() throws Exception {
    var failures = RuleCatalog.entries().stream()
        .map(entry -> finding(entry.ruleId(), Judgement.FAIL)).toList();
    Document document = write(failures);
    Element table = (Element) document.getElementsByTagNameNS(W, "tbl").item(0);
    var columns = table.getElementsByTagNameNS(W, "gridCol");
    assertEquals(3, columns.getLength());
    long width = 0;
    for (int i = 0; i < columns.getLength(); i++) {
      width += Long.parseLong(((Element) columns.item(i)).getAttributeNS(W, "w"));
    }
    assertEquals(Long.toString(width), first(table, "tblW").getAttributeNS(W, "w"));
    Element firstRow = (Element) table.getElementsByTagNameNS(W, "tr").item(0);
    assertEquals(1, firstRow.getElementsByTagNameNS(W, "tblHeader").getLength());
    for (int row = 0; row < table.getElementsByTagNameNS(W, "tr").getLength(); row++) {
      Element xmlRow = (Element) table.getElementsByTagNameNS(W, "tr").item(row);
      assertEquals(3, xmlRow.getElementsByTagNameNS(W, "tc").getLength());
      for (int col = 0; col < 3; col++) {
        Element cellWidth = (Element) xmlRow.getElementsByTagNameNS(W, "tcW").item(col);
        assertEquals(((Element) columns.item(col)).getAttributeNS(W, "w"),
            cellWidth.getAttributeNS(W, "w"));
      }
    }
    long pageWidth = Long.parseLong(first(document, "pgSz").getAttributeNS(W, "w"));
    Element margins = first(document, "pgMar");
    assertTrue(width <= pageWidth
        - Long.parseLong(margins.getAttributeNS(W, "left"))
        - Long.parseLong(margins.getAttributeNS(W, "right")));
  }

  @Test
  void doesNotIncludeLegacyDetailsCoverageOrAnnotationFields() throws Exception {
    Document document = write(List.of(finding("BODY-LINE", Judgement.FAIL),
        finding("EQ-03", Judgement.REVIEW)));
    String content = text(document);
    for (String removed : List.of("格式问题明细", "规则覆盖", "规则目录", "未检查区域",
        "批注", "BODY-LINE", "EQ-03", "实际值", "修改建议", "定位到的段落")) {
      assertFalse(content.contains(removed), "报告不应展示旧字段或内部信息: " + removed);
    }
    assertTrue(content.contains("待人工确认总数：1 项（不计入问题数量）"));
    assertTrue(content.contains("明确问题总数：1 项"));
  }

  private Document write(List<CheckResult> results) throws Exception {
    Path target = directory.resolve("report.docx");
    new WordReportWriter().write(new InspectionReport("private-source", "private-rules", results,
        List.of()), target);
    try (ZipFile zip = new ZipFile(target.toFile());
        var input = zip.getInputStream(zip.getEntry("word/document.xml"))) {
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

  private static Element first(Document document, String name) {
    var matches = document.getElementsByTagNameNS(W, name);
    assertTrue(matches.getLength() > 0, "缺少 w:" + name);
    return (Element) matches.item(0);
  }

  private static Element first(Element parent, String name) {
    var matches = parent.getElementsByTagNameNS(W, name);
    assertTrue(matches.getLength() > 0, "缺少 w:" + name);
    return (Element) matches.item(0);
  }

  private static List<List<String>> rows(Element table) {
    List<List<String>> result = new ArrayList<>();
    var xmlRows = table.getElementsByTagNameNS(W, "tr");
    for (int row = 0; row < xmlRows.getLength(); row++) {
      Element xmlRow = (Element) xmlRows.item(row);
      var cells = xmlRow.getElementsByTagNameNS(W, "tc");
      List<String> values = new ArrayList<>();
      for (int cell = 0; cell < cells.getLength(); cell++) {
        values.add(cells.item(cell).getTextContent().trim());
      }
      result.add(values);
    }
    return result;
  }

  private static String text(Document document) {
    return document.getDocumentElement().getTextContent();
  }
}
