package cn.luwneshacha.thesis.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.PageNumberModel;
import cn.luwneshacha.thesis.document.RawDocx;
import cn.luwneshacha.thesis.document.RawPart;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import cn.luwneshacha.thesis.rules.Applicability;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.ExecutionStatus;
import cn.luwneshacha.thesis.rules.Judgement;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentCoverageAnalyzerTest {
  @Test
  void groupsExecutedRulesAndListsUninspectedWordParts() {
    String document = """
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body><w:p><w:ins><w:r><w:t>revised text</w:t></w:r></w:ins></w:p>
            <w:p><w:r><w:txbxContent><w:p><w:r><w:t>textbox text</w:t></w:r></w:p></w:txbxContent></w:r></w:p>
          </w:body>
        </w:document>
        """;
    String header = """
        <w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:p><w:r><w:t>header text</w:t></w:r></w:p>
        </w:hdr>
        """;
    String footnotes = """
        <w:footnotes xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:footnote w:id="1"><w:p><w:r><w:t>note text</w:t></w:r></w:p></w:footnote>
        </w:footnotes>
        """;
    var parts = Map.of(
        "word/document.xml", part("word/document.xml", document),
        "word/header1.xml", part("word/header1.xml", header),
        "word/footnotes.xml", part("word/footnotes.xml", footnotes));
    var raw = new RawDocx("hash", new byte[]{1}, parts, parts.size());
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());
    var finding = new CheckResult("BODY-LINE", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.PASS, "360/auto", "360/auto", null,
        new SourceAddress("word/document.xml", List.of(0), 0, 0));

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of(finding));

    assertEquals(RuleCatalog.entries().size(), coverage.rules().size());
    var bodyLine = coverage.rules().stream()
        .filter(rule -> rule.ruleId().equals("BODY-LINE"))
        .findFirst().orElseThrow();
    assertEquals(1, bodyLine.passed());
    assertEquals("已执行且无错误", bodyLine.status());
    var equation = coverage.rules().stream()
        .filter(rule -> rule.ruleId().equals("EQ-01"))
        .findFirst().orElseThrow();
    assertEquals("未产生结果", equation.status());
    assertEquals("无结果证据", equation.executionStatus());
    assertTrue(coverage.uncheckedRegions().stream().anyMatch(region -> region.partName().equals("word/header1.xml")));
    assertTrue(coverage.uncheckedRegions().stream().anyMatch(region -> region.partName().equals("word/footnotes.xml")));
    assertTrue(coverage.uncheckedRegions().stream().anyMatch(region -> region.region().contains("文本框")));
    assertTrue(coverage.uncheckedRegions().stream().anyMatch(region -> region.region().contains("修订")));
    assertTrue(coverage.uncheckedRegions().stream().anyMatch(region -> region.partName().equals("word/header1.xml")
        && region.kind() == CoverageKind.NOT_INSPECTED && !region.suggestion().isBlank()));
    assertFalse(coverage.uncheckedRegions().stream().anyMatch(region -> region.partName().equals("word/header1.xml")
        && region.kind() == CoverageKind.NOT_ANNOTATABLE));
  }

  @Test
  void reportsMainDocumentTextWhenNoParsedContentWasExposed() {
    String document = """
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body><w:p><w:r><w:t>visible content</w:t></w:r></w:p></w:body>
        </w:document>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/document.xml", part("word/document.xml", document)), 1);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    var region = coverage.uncheckedRegions().stream()
        .filter(item -> item.region().equals("正文文字")).findFirst().orElseThrow();
    assertEquals(CoverageKind.NOT_INSPECTED, region.kind());
    assertFalse(region.suggestion().isBlank());
  }

  @Test
  void reportsAnXmlTableWithoutAMatchingParsedTablePath() {
    String document = """
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body><w:p/><w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl>
            <w:p/><w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></w:body>
        </w:document>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/document.xml", part("word/document.xml", document)), 1);
    var firstTable = new TableModel(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0),
        null, null, 1, 1, false, false, false, false, false, false);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of(), List.of(firstTable),
        List.of(), List.of(), List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    var missingTable = coverage.uncheckedRegions().stream()
        .filter(region -> region.region().equals("表格 XML 节点 [0, 3]"))
        .findFirst().orElseThrow();
    assertEquals(CoverageKind.NOT_INSPECTED, missingTable.kind());
    assertTrue(missingTable.reason().contains("没有对应表格模型"));
  }

  @Test
  void doesNotReportTablesWhoseXmlPathsHaveParsedModels() {
    String document = """
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body><w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></w:body>
        </w:document>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/document.xml", part("word/document.xml", document)), 1);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 0), 0, 0),
        null, null, 1, 1, false, false, false, false, false, false);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of(), List.of(table),
        List.of(), List.of(), List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertFalse(coverage.uncheckedRegions().stream().anyMatch(region -> region.region().contains("表格 XML 节点")));
  }

  @Test
  void footerPageFieldIsNotReportedAsOrdinaryUncheckedFooterText() {
    String footer = """
        <w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:p><w:fldSimple w:instr="PAGE"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p>
        </w:ftr>
        """;
    var raw = new RawDocx("hash", new byte[]{1}, Map.of("word/footer1.xml", part("word/footer1.xml", footer)), 1);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertTrue(coverage.uncheckedRegions().stream().noneMatch(region -> region.partName().equals("word/footer1.xml")));
  }

  @Test
  void pageNumberEvidenceDoesNotHideUncheckedOrdinaryFooterText() {
    String footer = """
        <w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:p><w:r><w:t>页脚说明</w:t></w:r><w:fldSimple w:instr="PAGE"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p>
        </w:ftr>
        """;
    var raw = new RawDocx("hash", new byte[]{1}, Map.of("word/footer1.xml", part("word/footer1.xml", footer)), 1);
    var pageNumber = new PageNumberModel(new SourceAddress("word/footer1.xml", List.of(0), 0, 1), true,
        true, "Times New Roman", 20);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(pageNumber), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertTrue(coverage.uncheckedRegions().stream().anyMatch(region -> region.partName().equals("word/footer1.xml")));
  }

  private static RawPart part(String name, String xml) {
    return new RawPart(name, "part-hash", xml.getBytes(StandardCharsets.UTF_8), true);
  }
}
