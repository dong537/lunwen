package cn.luwneshacha.thesis.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.ImageModel;
import cn.luwneshacha.thesis.document.ImageParser;
import cn.luwneshacha.thesis.document.FooterPageEvidence;
import cn.luwneshacha.thesis.document.HeaderModel;
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
  void reportsUnprovenGraphicInventoryEvenWhenAnotherPictureWasParsed() {
    String document = """
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
          <w:body>
            <w:p><w:r><w:drawing><pic:pic/></w:drawing></w:r></w:p>
            <w:p><w:r><w:drawing><a:graphic>
              <a:graphicData uri="urn:unrecognized-shape"/>
            </a:graphic></w:drawing></w:r></w:p>
          </w:body>
        </w:document>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/document.xml", part("word/document.xml", document)), 1);
    List<ImageModel> images = new ImageParser().parse(document.getBytes(StandardCharsets.UTF_8));
    var parsed = withImageInventory(images, false);

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertEquals(1, images.size());
    assertTrue(coverage.uncheckedRegions().stream().anyMatch(region ->
        region.partName().equals("word/document.xml")
            && region.region().equals("图片与图形识别范围")
            && region.kind() == CoverageKind.NOT_INSPECTED));
    assertFalse(new DocumentCoverageAnalyzer().analyze(raw, withImageInventory(images, true), List.of())
        .uncheckedRegions().stream().anyMatch(region -> region.region().equals("图片与图形识别范围")));
  }

  @Test
  void doesNotReportGraphicsWhenTheSourceHasNoneOrOnlyDeletedObjects() {
    for (String body : List.of(
        "<w:p><w:r><w:t>正文</w:t></w:r></w:p>",
        "<w:p><w:del><w:r><w:drawing/></w:r></w:del></w:p>",
        "<w:p><w:moveFrom><w:r><w:pict/></w:r></w:moveFrom></w:p>")) {
      String document = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
          + "<w:body>" + body + "</w:body></w:document>";
      var raw = new RawDocx("hash", new byte[]{1},
          Map.of("word/document.xml", part("word/document.xml", document)), 1);

      var coverage = new DocumentCoverageAnalyzer().analyze(raw,
          withImageInventory(List.of(), false), List.of());

      assertFalse(coverage.uncheckedRegions().stream().anyMatch(region ->
          region.region().equals("图片与图形识别范围")), body);
    }
  }

  @Test
  void strictWordprocessingNamespaceStillReportsUnprovenGraphicInventory() {
    String document = """
        <w:document xmlns:w="http://purl.oclc.org/ooxml/wordprocessingml/main">
          <w:body><w:p><w:r><w:drawing/></w:r></w:p></w:body>
        </w:document>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/document.xml", part("word/document.xml", document)), 1);

    var coverage = new DocumentCoverageAnalyzer().analyze(raw,
        withImageInventory(List.of(), false), List.of());

    assertTrue(coverage.uncheckedRegions().stream().anyMatch(region ->
        region.region().equals("图片与图形识别范围")
            && region.kind() == CoverageKind.NOT_INSPECTED));
  }

  private static ParsedDocument withImageInventory(List<ImageModel> images, boolean complete) {
    return new ParsedDocument("hash", List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), images, List.of(), List.of(), List.of(), List.of(),
        false, List.of(), false, List.of(), complete);
  }

  @Test
  void pureGraphicCompatibilityBranchKeepsOuterTextInspectableButGraphicUnknown() {
    String document = """
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:wp14="http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:v="urn:schemas-microsoft-com:vml">
          <w:body><w:p><w:pPr/><w:r><mc:AlternateContent>
            <mc:Choice Requires="wps"><w:drawing><wp:anchor>
              <wp:positionH><wp:posOffset>5133340</wp:posOffset></wp:positionH>
              <wp14:sizeRelH><wp14:pctWidth>0</wp14:pctWidth></wp14:sizeRelH>
              <a:graphic><a:graphicData
                uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">
              <wps:wsp><wps:spPr/></wps:wsp>
            </a:graphicData></a:graphic></wp:anchor></w:drawing></mc:Choice>
            <mc:Fallback><w:pict><v:line/></w:pict></mc:Fallback>
          </mc:AlternateContent></w:r><w:r><w:t>姓名</w:t></w:r></w:p></w:body>
        </w:document>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/document.xml", part("word/document.xml", document)), 1);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    var graphicGap = coverage.uncheckedRegions().stream()
        .filter(region -> region.region().startsWith("纯图形兼容内容分支"))
        .findFirst().orElseThrow();
    assertEquals(CoverageKind.NOT_INSPECTED, graphicGap.kind());
    assertFalse(InspectionKernel.requiresGlobalCompatibilityStop(coverage.uncheckedRegions()));

    String invalidPosition = document.replace("<wp:posOffset>5133340</wp:posOffset>",
        "<wp:posOffset>正文文字</wp:posOffset>");
    var invalidRaw = new RawDocx("hash", new byte[]{1},
        Map.of("word/document.xml", part("word/document.xml", invalidPosition)), 1);
    var invalidCoverage = new DocumentCoverageAnalyzer().analyze(invalidRaw, parsed, List.of());
    assertTrue(InspectionKernel.requiresGlobalCompatibilityStop(invalidCoverage.uncheckedRegions()));

    String branchText = document.replace("<w:drawing>", "ordinary text<w:drawing>");
    var branchTextRaw = new RawDocx("hash", new byte[]{1},
        Map.of("word/document.xml", part("word/document.xml", branchText)), 1);
    var branchTextCoverage = new DocumentCoverageAnalyzer().analyze(branchTextRaw, parsed, List.of());
    assertTrue(InspectionKernel.requiresGlobalCompatibilityStop(branchTextCoverage.uncheckedRegions()));
  }

  @Test
  void groupedGraphicAlternativeKeepsOrdinaryBodyRulesRunning() {
    String document = groupedGraphicDocument("");
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/document.xml", part("word/document.xml", document)), 1);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertEquals(1, coverage.uncheckedRegions().stream().filter(region ->
        region.region().startsWith("纯图形兼容内容分支")
            && region.kind() == CoverageKind.NOT_INSPECTED).count());
    assertFalse(InspectionKernel.requiresGlobalCompatibilityStop(coverage.uncheckedRegions()));
  }

  @Test
  void groupedGraphicAlternativeWithTextOrFormattingStillStopsBodyRules() {
    for (String extra : List.of("<w:txbxContent><w:p><w:r><w:t>图中文字</w:t></w:r></w:p></w:txbxContent>",
        "<a:t>图中文字</a:t>", "<w:pPr/>", "<w:rPr/>", "<wps:txbx/>")) {
      String document = groupedGraphicDocument(extra);
      var raw = new RawDocx("hash", new byte[]{1},
          Map.of("word/document.xml", part("word/document.xml", document)), 1);
      var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

      var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

      assertTrue(coverage.uncheckedRegions().stream().noneMatch(region ->
          region.region().startsWith("纯图形兼容内容分支")), extra);
      assertTrue(InspectionKernel.requiresGlobalCompatibilityStop(coverage.uncheckedRegions()), extra);
    }
  }

  private static String groupedGraphicDocument(String shapeExtra) {
    String shape = """
        <wps:wsp><wps:cNvPr/><wps:cNvSpPr/><wps:spPr>
          <a:xfrm><a:off/><a:ext/></a:xfrm><a:prstGeom><a:avLst/></a:prstGeom>
          <a:ln><a:solidFill><a:srgbClr/></a:solidFill><a:prstDash/>
            <a:headEnd/><a:tailEnd/></a:ln>
        </wps:spPr><wps:bodyPr/>%s</wps:wsp>
        """.formatted(shapeExtra);
    String line = "<v:line><v:fill/><v:stroke/><v:imagedata/><o:lock/></v:line>";
    return """
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:wpg="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup"
            xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:v="urn:schemas-microsoft-com:vml"
            xmlns:o="urn:schemas-microsoft-com:office:office">
          <w:body><w:p><w:r><mc:AlternateContent>
            <mc:Choice Requires="wpg"><w:drawing><wp:anchor><a:graphic><a:graphicData
                uri="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup">
              <wpg:wgp><wpg:cNvGrpSpPr/><wpg:grpSpPr><a:xfrm>
                <a:off/><a:ext/><a:chOff/><a:chExt/>
              </a:xfrm></wpg:grpSpPr>%s</wpg:wgp>
            </a:graphicData></a:graphic></wp:anchor></w:drawing></mc:Choice>
            <mc:Fallback><w:pict><v:group><o:lock/>%s</v:group></w:pict></mc:Fallback>
          </mc:AlternateContent></w:r></w:p>
          <w:p><w:r><w:t>正文仍需检查</w:t></w:r></w:p></w:body>
        </w:document>
        """.formatted(shape.repeat(6), line.repeat(6));
  }

  @Test
  void graphicBranchWithTextOrFormattingStillStopsUntrustedBody() {
    String prefix = """
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"
            xmlns:v="urn:schemas-microsoft-com:vml"
            xmlns:future="urn:example:unrecognized">
          <w:body><w:p><w:r><mc:AlternateContent>
            <mc:Choice Requires="wps"><w:drawing><wp:anchor><a:graphic><a:graphicData
                uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">
              <wps:wsp><wps:spPr/>
        """;
    String suffix = """
              </wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing>
            </mc:Choice><mc:Fallback><w:pict><v:line/></w:pict></mc:Fallback>
          </mc:AlternateContent></w:r></w:p></w:body></w:document>
        """;
    for (String unexpected : List.of("<w:t>other words</w:t>", "<a:t>drawing text</a:t>",
        "<w:rPr/>", "<w:pPr/>", "<w:fldChar w:fldCharType='begin'/>",
        "<future:unknown/>", "<w:numPr/>", "<w:tbl/>", "<w:txbxContent/>",
        "<m:oMath/>", "<w:object/>", "<wps:txbx/>")) {
      String document = prefix + unexpected + suffix;
      var raw = new RawDocx("hash", new byte[]{1},
          Map.of("word/document.xml", part("word/document.xml", document)), 1);
      var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

      var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

      assertTrue(coverage.uncheckedRegions().stream().noneMatch(region ->
          region.region().startsWith("纯图形兼容内容分支")), unexpected);
      assertTrue(InspectionKernel.requiresGlobalCompatibilityStop(coverage.uncheckedRegions()), unexpected);
    }
  }

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
    assertEquals("有检查结果，检查范围未确认", bodyLine.status());
    var equation = coverage.rules().stream()
        .filter(rule -> rule.ruleId().equals("EQ-01"))
        .findFirst().orElseThrow();
    assertEquals("未产生结果", equation.status());
    assertEquals("无结果证据", equation.executionStatus());
    assertFalse(coverage.uncheckedRegions().stream().anyMatch(region -> region.partName().equals("word/header1.xml")));
    assertTrue(coverage.uncheckedRegions().stream().anyMatch(region -> region.partName().equals("word/footnotes.xml")));
    assertTrue(coverage.uncheckedRegions().stream().anyMatch(region -> region.region().contains("文本框")));
    assertTrue(coverage.uncheckedRegions().stream().anyMatch(region -> region.region().contains("修订")));
    assertFalse(coverage.uncheckedRegions().stream().anyMatch(region -> region.partName().equals("word/header1.xml")
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
  void doesNotCountAnInactiveCompatibilityTableAsUnparsed() {
    String document = """
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math">
          <w:body><mc:AlternateContent>
            <mc:Choice Requires="m"><w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></mc:Choice>
            <mc:Fallback><w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></mc:Fallback>
          </mc:AlternateContent></w:body>
        </w:document>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/document.xml", part("word/document.xml", document)), 1);
    var table = new TableModel(new SourceAddress("word/document.xml", List.of(0, 0, 0, 0), 0, 0),
        null, null, 1, 1, false, false, false, false, false, false);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of(), List.of(table),
        List.of(), List.of(), List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertFalse(coverage.uncheckedRegions().stream().anyMatch(region -> region.region().contains("表格 XML 节点")));
  }

  @Test
  void deletedAndMovedOutTablesDoNotAppearAsUnparsedVisibleTables() {
    String document = """
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:body>
            <w:del><w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></w:del>
            <w:moveFrom><w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></w:moveFrom>
            <w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl>
          </w:body>
        </w:document>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/document.xml", part("word/document.xml", document)), 1);
    var visibleTable = new TableModel(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0),
        null, null, 1, 1, false, false, false, false, false, false);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of(),
        List.of(visibleTable), List.of(), List.of(), List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertFalse(coverage.uncheckedRegions().stream().anyMatch(region ->
        region.partName().equals("word/document.xml")
            && region.region().startsWith("表格 XML 节点")));
  }

  @Test
  void uncertainCompatibilityChoiceLeavesExplicitUninspectedEvidence() {
    String document = """
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:future="urn:example:future-choice">
          <w:body><mc:AlternateContent>
            <mc:Choice Requires="future"><w:p><w:r><w:t>未来版本内容</w:t></w:r></w:p></mc:Choice>
            <mc:Fallback><w:p><w:r><w:t>备用内容</w:t></w:r></w:p></mc:Fallback>
          </mc:AlternateContent></w:body>
        </w:document>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/document.xml", part("word/document.xml", document)), 1);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertTrue(coverage.uncheckedRegions().stream().anyMatch(region ->
        region.partName().equals("word/document.xml")
            && region.region().contains("兼容")
            && region.kind() == CoverageKind.NOT_INSPECTED
            && region.reason().contains("分支")));
  }

  @Test
  void uncertainChoiceDoesNotCreateAFalseMissingTableWhenNoBranchCanBeModeled() {
    String document = """
        <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:future="urn:example:future-choice">
          <w:body><mc:AlternateContent>
            <mc:Choice Requires="future"><w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></mc:Choice>
            <mc:Fallback><w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></mc:Fallback>
          </mc:AlternateContent></w:body>
        </w:document>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/document.xml", part("word/document.xml", document)), 1);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertFalse(coverage.uncheckedRegions().stream().anyMatch(region -> region.region().contains("表格 XML 节点")));
    assertTrue(coverage.uncheckedRegions().stream().anyMatch(region -> region.region().contains("兼容")
        && region.kind() == CoverageKind.NOT_INSPECTED));
  }

  @Test
  void uncertainBranchesInStylesAndNumberingKeepDocumentCoverageIncomplete() {
    String styles = """
        <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:future="urn:example:future-style">
          <mc:AlternateContent><mc:Choice Requires="future"><w:style w:styleId="BodyFuture"/></mc:Choice>
            <mc:Fallback><w:style w:styleId="BodyOlder"/></mc:Fallback></mc:AlternateContent>
        </w:styles>
        """;
    String numbering = """
        <w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:future="urn:example:future-numbering">
          <mc:AlternateContent><mc:Choice Requires="future"><w:num w:numId="1"/></mc:Choice>
            <mc:Fallback><w:num w:numId="2"/></mc:Fallback></mc:AlternateContent>
        </w:numbering>
        """;
    var raw = new RawDocx("hash", new byte[]{1}, Map.of(
        "word/styles.xml", part("word/styles.xml", styles),
        "word/numbering.xml", part("word/numbering.xml", numbering)), 2);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    for (String name : List.of("word/styles.xml", "word/numbering.xml")) {
      assertTrue(coverage.uncheckedRegions().stream().anyMatch(region ->
          region.partName().equals(name) && region.region().contains("兼容")
              && region.kind() == CoverageKind.NOT_INSPECTED));
    }
  }

  @Test
  void evenKnownChoiceInStylesAndNumberingNeedsClientViewConfirmation() {
    String styles = """
        <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006">
          <mc:AlternateContent><mc:Choice Requires="w"><w:style w:styleId="BodyChoice"/></mc:Choice>
            <mc:Fallback><w:style w:styleId="BodyFallback"/></mc:Fallback></mc:AlternateContent>
        </w:styles>
        """;
    String numbering = """
        <w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006">
          <mc:AlternateContent><mc:Choice Requires="w"><w:num w:numId="1"/></mc:Choice>
            <mc:Fallback><w:num w:numId="2"/></mc:Fallback></mc:AlternateContent>
        </w:numbering>
        """;
    var raw = new RawDocx("hash", new byte[]{1}, Map.of(
        "word/styles.xml", part("word/styles.xml", styles),
        "word/numbering.xml", part("word/numbering.xml", numbering)), 2);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    for (String name : List.of("word/styles.xml", "word/numbering.xml")) {
      assertTrue(coverage.uncheckedRegions().stream().anyMatch(region ->
          region.partName().equals(name) && region.region().contains("兼容")
              && region.kind() == CoverageKind.NOT_INSPECTED));
    }
  }

  @Test
  void alternativesOnlyInsideFormerPropertiesDoNotInvalidateCurrentFormatting() {
    String styles = """
        <w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006">
          <w:style w:styleId="Body"><w:pPr><w:pPrChange w:id="1"><w:pPr>
            <mc:AlternateContent><mc:Choice Requires="w"><w:spacing w:line="240"/></mc:Choice>
              <mc:Fallback><w:spacing w:line="360"/></mc:Fallback></mc:AlternateContent>
          </w:pPr></w:pPrChange></w:pPr></w:style>
        </w:styles>
        """;
    String numbering = """
        <w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006">
          <w:num w:numId="1"><w:numPrChange w:id="2"><w:numPr>
            <mc:AlternateContent><mc:Choice Requires="w"><w:numId w:val="4"/></mc:Choice>
              <mc:Fallback><w:numId w:val="5"/></mc:Fallback></mc:AlternateContent>
          </w:numPr></w:numPrChange></w:num>
        </w:numbering>
        """;
    var raw = new RawDocx("hash", new byte[]{1}, Map.of(
        "word/styles.xml", part("word/styles.xml", styles),
        "word/numbering.xml", part("word/numbering.xml", numbering)), 2);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertTrue(coverage.uncheckedRegions().isEmpty());
  }

  @Test
  void compatibilityMarkupInUnrelatedPackageMetadataIsNotAFormatCoverageGap() {
    String metadata = """
        <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:future="urn:example:metadata">
          <mc:AlternateContent><mc:Choice Requires="future"><cp:category>new</cp:category></mc:Choice>
            <mc:Fallback><cp:category>old</cp:category></mc:Fallback></mc:AlternateContent>
        </cp:coreProperties>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("docProps/core.xml", part("docProps/core.xml", metadata)), 1);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertTrue(coverage.uncheckedRegions().isEmpty());
  }

  @Test
  void unreferencedHeaderCompatibilityChoiceDoesNotBlockRules() {
    String header = """
        <w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:future="urn:example:future-choice">
          <mc:AlternateContent><mc:Choice Requires="future"><w:p><w:r><w:t>其他页眉</w:t></w:r></w:p></mc:Choice>
            <mc:Fallback><w:p><w:r><w:t>备用页眉</w:t></w:r></w:p></mc:Fallback></mc:AlternateContent>
        </w:hdr>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/header1.xml", part("word/header1.xml", header)), 1);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertFalse(coverage.uncheckedRegions().stream().anyMatch(region ->
        region.partName().equals("word/header1.xml")
            && region.region().contains("兼容")
            && region.kind() == CoverageKind.NOT_INSPECTED));
  }

  @Test
  void orphanHeaderAndFooterProseDoNotCreateCoverageGaps() {
    String header = """
        <w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:p><w:r><w:t>unused header</w:t></w:r></w:p>
        </w:hdr>
        """;
    String footer = """
        <w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
          <w:p><w:r><w:t>unused footer</w:t></w:r></w:p>
        </w:ftr>
        """;
    var raw = new RawDocx("hash", new byte[]{1}, Map.of(
        "word/header1.xml", part("word/header1.xml", header),
        "word/footer1.xml", part("word/footer1.xml", footer)), 2);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertTrue(coverage.uncheckedRegions().isEmpty());
  }

  @Test
  void onlyReferencedFooterAlternativesBlockFormattingRules() {
    String footer = """
        <w:ftr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:future="urn:example:future-choice">
          <mc:AlternateContent><mc:Choice Requires="future"><w:p><w:r><w:t>future</w:t></w:r></w:p></mc:Choice>
            <mc:Fallback><w:p><w:r><w:t>fallback</w:t></w:r></w:p></mc:Fallback></mc:AlternateContent>
        </w:ftr>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/footer1.xml", part("word/footer1.xml", footer)), 1);
    var unreferenced = new ParsedDocument("hash", List.of(), List.of(), List.of());
    var referenced = new ParsedDocument("hash", List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), false, List.of(), false,
        List.of(new FooterPageEvidence(0, PageNumberModel.FooterVariant.DEFAULT,
            FooterPageEvidence.Origin.EXPLICIT, "word/footer1.xml",
            FooterPageEvidence.Presence.UNKNOWN)));

    var analyzer = new DocumentCoverageAnalyzer();

    assertFalse(analyzer.analyze(raw, unreferenced, List.of()).uncheckedRegions().stream()
        .anyMatch(region -> region.partName().equals("word/footer1.xml")
            && region.region().contains("兼容")));
    assertTrue(analyzer.analyze(raw, referenced, List.of()).uncheckedRegions().stream()
        .anyMatch(region -> region.partName().equals("word/footer1.xml")
            && region.region().contains("兼容") && region.kind() == CoverageKind.NOT_INSPECTED));
  }

  @Test
  void settingsCompatibilityChoiceLeavesPageVariantEvidenceUninspected() {
    String settings = """
        <w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:future="urn:example:future-choice">
          <mc:AlternateContent><mc:Choice Requires="future"><w:evenAndOddHeaders/></mc:Choice>
            <mc:Fallback><w:evenAndOddHeaders w:val="0"/></mc:Fallback></mc:AlternateContent>
        </w:settings>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/settings.xml", part("word/settings.xml", settings)), 1);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertTrue(coverage.uncheckedRegions().stream().anyMatch(region ->
        region.partName().equals("word/settings.xml")
            && region.region().contains("兼容") && region.kind() == CoverageKind.NOT_INSPECTED));
  }

  @Test
  void unrelatedSettingsCompatibilityChoiceDoesNotBlockSchoolRules() {
    String settings = """
        <w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:custom="urn:example:custom-data">
          <mc:AlternateContent><mc:Choice Requires="custom">
            <custom:typoFeatureVersion val="1"/>
          </mc:Choice></mc:AlternateContent>
        </w:settings>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/settings.xml", part("word/settings.xml", settings)), 1);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertTrue(coverage.uncheckedRegions().isEmpty());
  }

  @Test
  void themeFontLangInFallbackRemainsBlockingSettingAlternative() {
    String settings = """
        <w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:future="urn:example:future-choice">
          <mc:AlternateContent><mc:Choice Requires="future"><w:otherSetting/></mc:Choice>
            <mc:Fallback><w:themeFontLang w:val="en-US"/></mc:Fallback></mc:AlternateContent>
        </w:settings>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/settings.xml", part("word/settings.xml", settings)), 1);
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of());

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertTrue(coverage.uncheckedRegions().stream().anyMatch(region ->
        region.partName().equals("word/settings.xml")
            && region.region().contains("兼容") && region.kind() == CoverageKind.NOT_INSPECTED));
  }

  @Test
  void parsedHeaderStillReportsAnUnknownCompatibilityBranch() {
    String header = """
        <w:hdr xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:future="urn:example:future-choice">
          <mc:AlternateContent><mc:Choice Requires="future"><w:p><w:r><w:t>新版页眉</w:t></w:r></w:p></mc:Choice>
            <mc:Fallback><w:p><w:r><w:t>旧版页眉</w:t></w:r></w:p></mc:Fallback></mc:AlternateContent>
        </w:hdr>
        """;
    var raw = new RawDocx("hash", new byte[]{1},
        Map.of("word/header1.xml", part("word/header1.xml", header)), 1);
    var modeledHeader = new HeaderModel(
        new SourceAddress("word/header1.xml", List.of(0), 0, 0),
        "旧版页眉", true, List.of(), List.of(0));
    var parsed = new ParsedDocument("hash", List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(modeledHeader));

    var coverage = new DocumentCoverageAnalyzer().analyze(raw, parsed, List.of());

    assertTrue(coverage.uncheckedRegions().stream().anyMatch(region ->
        region.partName().equals("word/header1.xml") && region.region().contains("兼容")
            && region.kind() == CoverageKind.NOT_INSPECTED));
    assertFalse(coverage.uncheckedRegions().stream().anyMatch(region ->
        region.partName().equals("word/header1.xml") && region.region().equals("页眉文字格式")));
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
