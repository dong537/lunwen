package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.*;

import cn.luwneshacha.thesis.document.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SplitTableEvidenceTest {
  @Test
  void checksTheActualTailOfAStructurallySplitTable() {
    var document = document("<w:p/><w:p/>", "<w:p/>", "1-1", "2000", "结果", true, "尾行");
    var findings = findings(document);
    assertFalse(document.tables().get(0).bottomBorder(), "physical cell nil must remain authoritative");
    assertTrue(document.tables().get(1).bottomBorder());
    assertFalse(findings.stream().anyMatch(item -> item.ruleId().equals("TAB-02")), findings.toString());
    assertFalse(findings.stream().anyMatch(item -> item.reasonCode().equals("TABLE_CAPTION_MISSING")), findings.toString());
    assertFalse(findings.stream().anyMatch(item -> item.ruleId().equals("TAB-08")), findings.toString());
  }

  @Test
  void anchorsARealMissingBottomAtTheTailInsteadOfTheOriginalTable() {
    var document = document("<w:p/>", "", "1-1", "2000", "结果", false, "尾行");
    var borders = findings(document).stream().filter(item -> item.ruleId().equals("TAB-02")).toList();
    assertEquals(1, borders.size(), borders.toString());
    assertEquals("底线", borders.get(0).expected());
    assertEquals(document.tables().get(1).source(), borders.get(0).source());
  }

  @Test
  void aTailCellExplicitlyCancellingItsBottomStillFails() {
    String original = table("2000", true, true, false,
        row("字段", "结果", true) + row("数据", "1", false));
    String tail = table("2000", false, false, true,
        "<w:tr>" + cell("尾行", true) + cell("2", false) + "</w:tr>");
    String continuation = table("2000", true, true, true,
        row("字段", "结果", true) + row("下一行", "3", true));
    var document = parsed(paragraph("表1-1 测试数据") + original + tail
        + paragraph("续表1-1") + continuation);
    assertNotNull(document.tables().get(1).fragmentEvidence());
    assertFalse(document.tables().get(1).bottomBorder());
    assertTrue(findings(document).stream().anyMatch(item -> item.ruleId().equals("TAB-02")
        && "底线".equals(item.expected()) && document.tables().get(1).source().equals(item.source())));
  }

  @Test
  void keepsContentChecksOnTheTailFragment() {
    var document = document("", "", "1-1", "2000", "结果", true, "同前");
    assertTrue(findings(document).stream().anyMatch(item ->
        item.reasonCode().equals("TABLE_REPEATED_PLACEHOLDER")
            && item.source().equals(document.tables().get(1).source())));
  }

  @Test
  void doesNotJoinAcrossTextObjectsSectionsOrDifferentContainers() {
    for (String blocker : List.of(paragraph("正文不能越过"), "<w:p><w:r><w:drawing/></w:r></w:p>",
        "<w:p><w:r><w:pict/></w:r></w:p>", "<w:p><w:r><w:object/></w:r></w:p>",
        "<w:p><m:oMath xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\"/></w:p>",
        "<w:p><w:pPr><w:sectPr/></w:pPr></w:p>")) {
      var document = document(blocker, "", "1-1", "2000", "结果", true, "尾行");
      assertIndependentTail(document, blocker);
    }
    var document = document("", paragraph("中间正文"), "1-1", "2000", "结果", true, "尾行");
    assertIndependentTail(document, "text between tail and continuation");
  }

  @Test
  void doesNotJoinTableElementsFromDifferentXmlContainers() {
    String original = table("2000", true, true, false,
        row("字段", "结果", true) + row("数据", "1", false));
    String tail = table("2000", false, false, true, row("尾行", "2", true));
    String continuation = table("2000", true, true, true,
        row("字段", "结果", true) + row("下一行", "3", true));
    var document = parsed(paragraph("表1-1 测试数据") + original
        + "<w:customXml w:uri=\"urn:example\" w:element=\"fragment\">" + tail + "</w:customXml>"
        + paragraph("续表1-1") + continuation);
    assertIndependentTail(document, "different XML parents");
  }

  @Test
  void doesNotJoinDifferentNumbersGeometryOrHeaders() {
    assertIndependentTail(document("", "", "1-2", "2000", "结果", true, "尾行"), "number");
    assertIndependentTail(document("", "", "1-1", "2001", "结果", true, "尾行"), "grid");
    assertIndependentTail(document("", "", "1-1", "2000", "不同表头", true, "尾行"), "header");
  }

  @Test
  void preservesASeparateRepeatedHeaderWithoutBorrowingItsBottom() {
    String original = table("2000", true, true, false,
        row("字段", "结果", true) + row("数据", "1", false));
    String repeat = table("2000", true, false, true, row("字段", "结果", true));
    String continuation = table("2000", true, true, true,
        row("字段", "结果", true) + row("下一行", "2", true));
    var document = parsed(paragraph("表1-1 测试数据") + original + repeat
        + paragraph("续表1-1") + continuation);
    assertEquals(TableFragmentEvidence.Kind.REPEATED_HEADER,
        document.tables().get(1).fragmentEvidence().kind());
    var findings = findings(document);
    assertTrue(findings.stream().anyMatch(item -> item.ruleId().equals("TAB-02")
        && document.tables().get(0).source().equals(item.source())));
    assertFalse(findings.stream().anyMatch(item -> item.reasonCode().equals("TABLE_CAPTION_MISSING")
        || item.ruleId().equals("TAB-08")), findings.toString());
  }

  @Test
  void usesASeparateRepeatedHeaderForADataOnlyContinuation() {
    for (String repeatedCaption : List.of("", paragraph("表1-1 测试数据"))) {
      var document = repeatedHeaderAndDataContinuation(repeatedCaption, "", "2000", "结果");
      assertNotNull(document.tables().get(1).fragmentEvidence());
      assertEquals(TableFragmentEvidence.Kind.REPEATED_HEADER,
          document.tables().get(1).fragmentEvidence().kind());
      assertFalse(findings(document).stream().anyMatch(item -> item.ruleId().equals("TAB-08")),
          findings(document).toString());
      assertFalse(findings(document).stream().anyMatch(item -> item.ruleId().equals("TAB-05")),
          "a repeated header is not a second table number");
    }
  }

  @Test
  void rejectsUnprovenRepeatedHeadersForDataOnlyContinuations() {
    for (var document : List.of(
        repeatedHeaderAndDataContinuation(paragraph("表1-2 其他表"), "", "2000", "结果"),
        repeatedHeaderAndDataContinuation("", paragraph("不能跨越的正文"), "2000", "结果"),
        repeatedHeaderAndDataContinuation("", "<w:p><w:r><w:drawing/></w:r></w:p>", "2000", "结果"),
        repeatedHeaderAndDataContinuation("", "", "2001", "结果"),
        repeatedHeaderAndDataContinuation("", "", "2000", "错误表头"))) {
      assertNull(document.tables().get(1).fragmentEvidence());
      assertTrue(findings(document).stream().anyMatch(item -> item.ruleId().equals("TAB-08")),
          findings(document).toString());
    }
  }

  private static ParsedDocument repeatedHeaderAndDataContinuation(String repeatedCaption,
      String beforeRepeatedHeader, String gridWidth, String secondHeader) {
    String original = table("2000", true, true, true,
        row("字段", "结果", true) + row("数据", "1", true));
    String repeatedHeader = table(gridWidth, true, false, true,
        row("字段", secondHeader, true));
    String dataOnlyContinuation = table("2000", true, true, true,
        row("后置条件", "数据可被查询", true) + row("下一行", "2", true));
    return parsed(paragraph("表1-1 测试数据") + original + beforeRepeatedHeader + repeatedCaption
        + repeatedHeader + paragraph("续表1-1") + dataOnlyContinuation);
  }

  @Test
  void usesOriginalQuantityHeadersWhenCheckingTailCellUnits() {
    for (String tailValue : List.of("2 cm", "2")) {
      String original = table("2000", true, true, false,
          row("项目", "长度", true) + row("数据", "1 cm", false));
      String tail = table("2000", false, false, true, row("尾行", tailValue, true));
      String continuation = table("2000", true, true, true,
          row("项目", "长度", true) + row("下一行", "3 cm", true));
      var document = parsed(paragraph("表1-1 测试数据") + original + tail
          + paragraph("续表1-1") + continuation);
      var findings = findings(document);
      boolean missingUnit = findings.stream().anyMatch(item -> item.ruleId().equals("TAB-04")
          && document.tables().get(1).source().equals(item.source()));
      assertEquals(tailValue.equals("2"), missingUnit, findings.toString());
    }
  }

  @Test
  void doesNotTreatAnUnresolvedTailStyleAsVerifiedBorders() {
    String original = table("2000", true, true, false,
        row("字段", "结果", true) + row("数据", "1", false));
    String tail = table("2000", false, false, false, row("尾行", "2", false))
        .replace("<w:tblPr>", "<w:tblPr><w:tblStyle w:val=\"UnknownStyle\"/>");
    String continuation = table("2000", true, true, true,
        row("字段", "结果", true) + row("下一行", "3", true));
    var document = parsed(paragraph("表1-1 测试数据") + original + tail
        + paragraph("续表1-1") + continuation);
    var findings = findings(document);
    assertTrue(findings.stream().anyMatch(item -> item.ruleId().equals("TAB-02")
        && item.judgement() == Judgement.REVIEW
        && document.tables().get(1).source().equals(item.source())), findings.toString());
  }

  @Test
  void repeatedQuantityHeadersDoNotLoseTheOriginalCellUnitEvidence() {
    String original = table("2000", true, true, true,
        row("项目", "温度", true) + row("数据", "25℃", true));
    String repeat = table("2000", true, false, true, row("项目", "温度", true));
    String continuation = table("2000", true, true, true,
        row("项目", "温度", true) + row("下一行", "26℃", true));
    var document = parsed(paragraph("表1-1 测试数据") + original + repeat
        + paragraph("续表1-1") + continuation);
    assertNotNull(document.tables().get(1).fragmentEvidence());
    assertFalse(findings(document).stream().anyMatch(item -> item.ruleId().equals("TAB-04")));
  }

  @Test
  void doesNotJoinAnIndependentRowAfterACompletedTable() {
    String original = table("2000", true, true, true,
        row("字段", "结果", true) + row("数据", "1", true));
    String tail = table("2000", false, false, true, row("独立行", "2", true));
    String continuation = table("2000", true, true, true,
        row("字段", "结果", true) + row("下一行", "3", true));
    var document = parsed(paragraph("表1-1 测试数据") + original + tail
        + paragraph("续表1-1") + continuation);
    assertNull(document.tables().get(1).fragmentEvidence());
    assertTrue(findings(document).stream().anyMatch(item -> item.reasonCode().equals("TABLE_CAPTION_MISSING")
        && document.tables().get(1).source().equals(item.source())));
  }

  @Test
  void appendixFragmentsDoNotRequireTheirOwnObjectNumber() {
    String original = table("2000", true, true, false,
        row("字段", "结果", true) + row("数据", "1", false));
    String tail = table("2000", false, false, true, row("尾行", "2", true));
    String continuation = table("2000", true, true, true,
        row("字段", "结果", true) + row("下一行", "3", true));
    var parsed = parsed(paragraph("附    录") + paragraph("附录A 数据")
        + paragraph("表A1 测试数据") + original + tail + paragraph("续表A1") + continuation);
    var titleSource = new SourceAddress("word/document.xml", List.of(0, 1), 0, 6);
    var letterSource = new SourceAddress("word/document.xml", List.of(0, 2), 0, 6);
    var paragraphs = new java.util.ArrayList<>(parsed.paragraphs());
    paragraphs.add(heading("附    录", titleSource));
    paragraphs.add(heading("附录A 数据", letterSource));
    var document = new ParsedDocument("source", paragraphs, List.of(), List.of(), parsed.tables(), List.of(), List.of());
    assertNotNull(document.tables().get(1).fragmentEvidence());
    assertFalse(new AppendixRuleModule().check(document, RuleSet.schoolV1()).stream().anyMatch(item ->
        document.tables().get(1).source().equals(item.source())), "fragment is not an independent appendix object");
  }

  private static ParagraphModel heading(String text, SourceAddress source) {
    var run = new RunModel(text, "Times New Roman", "Times New Roman", "黑体", null,
        32, null, false, null, source);
    return new ParagraphModel(List.of(run), null, null, null, 360, "auto", source,
        null, null, "center");
  }

  private static void assertIndependentTail(ParsedDocument document, String reason) {
    var findings = findings(document);
    assertTrue(findings.stream().anyMatch(item -> item.reasonCode().equals("TABLE_CAPTION_MISSING")
        && item.source().equals(document.tables().get(1).source())), reason + findings);
    assertTrue(findings.stream().anyMatch(item -> item.ruleId().equals("TAB-02")
        && item.source().equals(document.tables().get(0).source())), reason + findings);
  }

  private static List<CheckResult> findings(ParsedDocument document) {
    return new TableRuleModule().check(document, RuleSet.schoolV1());
  }

  private static ParsedDocument document(String beforeTail, String afterTail, String continuationNumber,
      String tailGridWidth, String continuationHeader, boolean tailBottom, String tailText) {
    String original = table("2000", true, true, false,
        row("字段", "结果", true) + row("数据", "1", false));
    String tail = table(tailGridWidth, false, false, tailBottom, row(tailText, "2", tailBottom));
    String continuation = table("2000", true, true, true,
        row("字段", continuationHeader, true) + row("续行", "3", true));
    return parsed(paragraph("表1-1 测试数据") + original + beforeTail + tail
        + afterTail + paragraph("续表" + continuationNumber) + continuation);
  }

  private static ParsedDocument parsed(String body) {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + paragraph("第一章  测试") + body + "</w:body></w:document>";
    var tables = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8));
    var source = new SourceAddress("word/document.xml", List.of(0, 0), 0, 7);
    var run = new RunModel("第一章  测试", "Times New Roman", "Times New Roman", "黑体", null,
        32, null, false, null, source);
    var chapter = new ParagraphModel(List.of(run), null, null, null, 360, "auto", source,
        null, null, "center");
    return new ParsedDocument("source", List.of(chapter), List.of(), List.of(), tables, List.of(), List.of());
  }

  private static String table(String gridWidth, boolean top, boolean middle, boolean bottom, String rows) {
    return "<w:tbl><w:tblPr><w:tblBorders>" + border("top", top) + border("insideH", middle)
        + border("bottom", bottom) + "</w:tblBorders></w:tblPr><w:tblGrid>"
        + "<w:gridCol w:w=\"2000\"/><w:gridCol w:w=\"" + gridWidth + "\"/></w:tblGrid>"
        + rows + "</w:tbl>";
  }

  private static String row(String first, String second, boolean bottom) {
    return "<w:tr>" + cell(first, bottom) + cell(second, bottom) + "</w:tr>";
  }

  private static String cell(String text, boolean bottom) {
    return "<w:tc><w:tcPr><w:tcBorders>" + border("bottom", bottom)
        + "</w:tcBorders></w:tcPr>" + paragraph(text) + "</w:tc>";
  }

  private static String border(String side, boolean present) {
    return "<w:" + side + " w:val=\"" + (present ? "single" : "nil") + "\"/>";
  }

  private static String paragraph(String text) {
    return "<w:p><w:r><w:t>" + text + "</w:t></w:r></w:p>";
  }
}
