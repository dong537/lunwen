package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class TableParserTest {
  @Test
  void linksCaptionBeforeTableAndReadsThreeLineEvidence() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>表1-1 测试数据</w:t></w:r></w:p><w:tbl><w:tblPr><w:tblBorders>"
        + "<w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/><w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tblBorders></w:tblPr>"
        + "<w:tr><w:tc><w:p/></w:tc></w:tr><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";
    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);
    assertEquals("表1-1 测试数据", table.captionText());
    assertTrue(table.captionBefore());
    assertFalse(table.layoutNeedsReview());
    assertTrue(table.topBorder() && table.middleBorder() && table.bottomBorder());
    assertFalse(table.verticalBorders());
    assertFalse(table.hasTableStyle());
  }

  @Test
  void readsRowsWrappedByCustomXmlBlock() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:customXml w:uri=\"urn:example\" w:element=\"rows\">"
        + "<w:tr><w:tc><w:p><w:r><w:t>表头</w:t></w:r></w:p></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p><w:r><w:t>数据</w:t></w:r></w:p></w:tc></w:tr>"
        + "</w:customXml></w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals(2, table.rows());
    assertEquals(1, table.columns());
    assertEquals(java.util.List.of("表头"), table.headerTexts());
  }

  @Test
  void doesNotTreatDottedTopBorderAsTheRequiredSingleLine() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tblPr><w:tblBorders><w:top w:val=\"dotted\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
        + "</w:tblBorders></w:tblPr><w:tr><w:tc><w:p/></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertFalse(table.topBorder());
  }

  @Test
  void acceptsRequiredSingleLineWithoutInventingAnUnspecifiedBorderWidth() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"8\" w:color=\"FF0000\" w:space=\"2\"/>"
        + "</w:tblBorders></w:tblPr><w:tr><w:tc><w:p/></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertTrue(table.topBorder());
  }

  @Test
  void readsRightAlignedSharedUnitBetweenCaptionAndTable() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>表1-1 测试数据</w:t></w:r></w:p>"
        + "<w:p><w:pPr><w:jc w:val=\"right\"/></w:pPr><w:r><w:t>单位：mm</w:t></w:r></w:p>"
        + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>长度</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals("表1-1 测试数据", table.captionText());
    assertTrue(table.captionBefore());
    assertEquals("单位：mm", table.sharedUnitText());
    assertTrue(table.sharedUnitRightAligned());
  }

  @Test
  void resolvesRightAlignedSharedUnitThroughParagraphStyleInheritance() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>表1-1 测试数据</w:t></w:r></w:p>"
        + "<w:p><w:pPr><w:pStyle w:val=\"UnitLine\"/></w:pPr>"
        + "<w:r><w:t>单位：mm</w:t></w:r></w:p>"
        + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>长度</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";
    String styles = "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
        + "<w:style w:type=\"paragraph\" w:styleId=\"RightBase\"><w:pPr><w:jc w:val=\"right\"/>"
        + "</w:pPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"UnitLine\"><w:basedOn w:val=\"RightBase\"/>"
        + "</w:style></w:styles>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8),
        styles.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals("单位：mm", table.sharedUnitText());
    assertTrue(table.sharedUnitRightAligned());
  }

  @Test
  void readsPlainUnitOnRightSideOfTableTopLine() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>表1-2 测试数据</w:t></w:r></w:p>"
        + "<w:p><w:pPr><w:jc w:val=\"right\"/></w:pPr><w:r><w:t>mm</w:t></w:r></w:p>"
        + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>长度</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals("mm", table.sharedUnitText());
    assertTrue(table.sharedUnitRightAligned());
  }

  @Test
  void recordsSharedUnitButDoesNotClaimItIsOnTheRightWithoutAlignmentEvidence() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>表1-1 测试数据</w:t></w:r></w:p>"
        + "<w:p><w:pPr><w:jc w:val=\"left\"/></w:pPr><w:r><w:t>单位：mm</w:t></w:r></w:p>"
        + "<w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals("单位：mm", table.sharedUnitText());
    assertFalse(table.sharedUnitRightAligned());
  }

  @Test
  void recognizesSharedUnitWrittenWithNonBreakingSpaces() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>表1-1 数据</w:t></w:r></w:p>"
        + "<w:p><w:pPr><w:jc w:val=\"right\"/></w:pPr><w:r><w:t>单位\u00a0：\u00a0mm</w:t></w:r></w:p>"
        + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>长度</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals("单位\u00a0：\u00a0mm", table.sharedUnitText());
    assertTrue(table.sharedUnitRightAligned());
  }

  @Test
  void preservesCaptionWhitespaceForExactRuleValidation() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t xml:space=\"preserve\"> 表3-1　测试数据 </w:t></w:r></w:p>"
        + "<w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals(" 表3-1　测试数据 ", table.captionText());
  }

  @Test
  void recordsExplicitTableStyleForLayoutClassification() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/></w:tblPr>"
        + "<w:tr><w:tc><w:p/></w:tc><w:tc><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertTrue(table.hasTableStyle());
  }

  @Test
  void resolvesUnstyledTableWhenStylesPartHasNoDefaultTableStyle() {
    String document = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";
    String styles = "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
        + "<w:style w:type=\"table\" w:styleId=\"CustomTable\"><w:tblPr><w:tblBorders>"
        + "<w:top w:val=\"single\" w:sz=\"4\"/></w:tblBorders></w:tblPr></w:style></w:styles>";

    var table = new TableParser().parse(document.getBytes(StandardCharsets.UTF_8),
        styles.getBytes(StandardCharsets.UTF_8)).get(0);

    assertTrue(table.tableStyleBorderEvidenceResolved());
    assertFalse(table.hasTableStyle());
    assertFalse(table.topBorder());
  }

  @Test
  void resolvesInheritedWholeTableBordersFromTableStyles() {
    String document = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tblPr><w:tblStyle w:val=\"ThesisTable\"/></w:tblPr>"
        + "<w:tr><w:tc><w:p/></w:tc></w:tr><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";
    String styles = "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
        + "<w:style w:type=\"table\" w:styleId=\"BaseTable\"><w:tblPr><w:tblBorders>"
        + "<w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tblBorders></w:tblPr></w:style>"
        + "<w:style w:type=\"table\" w:styleId=\"ThesisTable\"><w:basedOn w:val=\"BaseTable\"/>"
        + "<w:tblStylePr w:type=\"wholeTable\"><w:tblPr><w:tblBorders>"
        + "<w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tblBorders></w:tblPr></w:tblStylePr></w:style></w:styles>";

    var table = new TableParser().parse(document.getBytes(StandardCharsets.UTF_8),
        styles.getBytes(StandardCharsets.UTF_8)).get(0);

    assertTrue(table.hasTableStyle());
    assertTrue(table.tableStyleBorderEvidenceResolved());
    assertTrue(table.topBorder() && table.middleBorder() && table.bottomBorder());
  }

  @Test
  void doesNotClaimUnsupportedConditionalTableBordersAreResolved() {
    String document = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tblPr><w:tblStyle w:val=\"ConditionalTable\"/></w:tblPr>"
        + "<w:tr><w:tc><w:p/></w:tc></w:tr><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";
    String styles = "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
        + "<w:style w:type=\"table\" w:styleId=\"ConditionalTable\"><w:tblStylePr w:type=\"firstRow\">"
        + "<w:tcPr><w:tcBorders><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tcBorders></w:tcPr>"
        + "</w:tblStylePr></w:style></w:styles>";

    var table = new TableParser().parse(document.getBytes(StandardCharsets.UTF_8),
        styles.getBytes(StandardCharsets.UTF_8)).get(0);

    assertTrue(table.hasTableStyle());
    assertFalse(table.tableStyleBorderEvidenceResolved());
  }

  @Test
  void marksLegacyHorizontalMergeBorderEvidenceUnresolved() {
    String document = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:tcPr><w:hMerge w:val=\"restart\"/></w:tcPr><w:p/></w:tc>"
        + "<w:tc><w:tcPr><w:hMerge w:val=\"continue\"/></w:tcPr><w:p/></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p/></w:tc><w:tc><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";

    var table = new TableParser().parse(document.getBytes(StandardCharsets.UTF_8)).get(0);

    assertFalse(table.tableStyleBorderEvidenceResolved());
  }

  @Test
  void readsThreeLineEvidenceStoredOnCellsWhenTableBordersAreAbsent() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:tcPr><w:tcBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tcBorders></w:tcPr><w:p/></w:tc></w:tr>"
        + "<w:tr><w:tc><w:tcPr><w:tcBorders><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tcBorders></w:tcPr><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertTrue(table.topBorder() && table.middleBorder() && table.bottomBorder());
    assertFalse(table.verticalBorders());
  }

  @Test
  void doesNotTreatMisplacedCellBordersAsOuterTableEdges() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:tcPr><w:tcBorders><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tcBorders></w:tcPr><w:p/></w:tc></w:tr>"
        + "<w:tr><w:tc><w:tcPr><w:tcBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tcBorders></w:tcPr><w:p/></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertFalse(table.topBorder());
    assertTrue(table.middleBorder());
    assertFalse(table.bottomBorder());
  }

  @Test
  void includesRowExceptionBordersWhenResolvingTableLines() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tblPrEx><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
        + "<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tblBorders></w:tblPrEx><w:tc><w:p/></w:tc></w:tr>"
        + "<w:tr><w:tblPrEx><w:tblBorders><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tblBorders></w:tblPrEx>"
        + "<w:tc><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertTrue(table.topBorder());
    assertTrue(table.middleBorder());
    assertTrue(table.bottomBorder());
  }

  @Test
  void doesNotTreatBorderBelowLaterBodyRowAsHeaderSeparator() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:tcPr><w:tcBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tcBorders></w:tcPr><w:p/></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p/></w:tc></w:tr>"
        + "<w:tr><w:tc><w:tcPr><w:tcBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tcBorders></w:tcPr><w:p/></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertTrue(table.topBorder());
    assertFalse(table.middleBorder());
    assertTrue(table.bottomBorder());
  }

  @Test
  void doesNotTreatPartialCellEdgesAsFullWidthThreeLineTableBorders() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:tcPr><w:tcBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tcBorders></w:tcPr><w:p/></w:tc><w:tc><w:p/></w:tc></w:tr>"
        + "<w:tr><w:tc><w:tcPr><w:tcBorders><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tcBorders></w:tcPr><w:p/></w:tc><w:tc><w:p/></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertFalse(table.topBorder());
    assertFalse(table.middleBorder());
    assertFalse(table.bottomBorder());
  }

  @Test
  void recognizesFullWidthBordersAcrossMergedHeaderCells() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tblGrid><w:gridCol w:w=\"1200\"/><w:gridCol w:w=\"1200\"/></w:tblGrid>"
        + "<w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/><w:tcBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tcBorders></w:tcPr><w:p/></w:tc></w:tr>"
        + "<w:tr><w:tc><w:tcPr><w:tcBorders><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tcBorders></w:tcPr><w:p/></w:tc><w:tc><w:tcPr><w:tcBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tcBorders></w:tcPr><w:p/></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertTrue(table.topBorder());
    assertTrue(table.middleBorder());
    assertTrue(table.bottomBorder());
  }

  @Test
  void recognizesThreeLinesWhenTrailingGridSlotsAreOmittedUntilLaterMergedRows() {
    String top = singleBorder("top");
    String bottom = singleBorder("bottom");
    String rows = borderRow(0, 1, borderCell(1, top + bottom) + borderCell(1, top + bottom))
        + borderRow(0, 1, borderCell(1, top) + borderCell(1, top))
        + borderRow(0, 1, borderCell(1, "") + borderCell(1, "")).repeat(3)
        + borderRow(0, 0, borderCell(1, "") + borderCell(2, ""))
        + borderRow(0, 0, borderCell(1, bottom) + borderCell(2, bottom));

    var table = parseBorderTable(3, rows);

    assertEquals(7, table.rows());
    assertTrue(table.xmlNumericValuesValid());
    assertAll(() -> assertTrue(table.topBorder()), () -> assertTrue(table.middleBorder()),
        () -> assertTrue(table.bottomBorder()));
  }

  @Test
  void recognizesBordersOnlyAcrossRealCellsBetweenLeadingAndTrailingOmittedSlots() {
    String borders = singleBorder("top") + singleBorder("bottom");
    String rows = borderRow(2, 1, borderCell(2, borders) + borderCell(1, borders)).repeat(2);

    var table = parseBorderTable(6, rows);

    assertAll(() -> assertTrue(table.topBorder()), () -> assertTrue(table.middleBorder()),
        () -> assertTrue(table.bottomBorder()));
  }

  @Test
  void doesNotRequireOuterRowsToCoverGridColumnsUsedOnlyByAnotherRow() {
    String borders = singleBorder("top") + singleBorder("bottom");
    String rows = borderRow(0, 0, borderCell(1, borders).repeat(2))
        + borderRow(0, 0, borderCell(1, "").repeat(4))
        + borderRow(0, 0, borderCell(1, singleBorder("bottom")).repeat(2));

    var table = parseBorderTable(4, rows);

    assertAll(() -> assertTrue(table.topBorder()), () -> assertTrue(table.middleBorder()),
        () -> assertTrue(table.bottomBorder()));
  }

  @Test
  void checksHeaderSeparatorOnlyWhereRealCellsOnBothRowsAreAdjacent() {
    String rows = borderRow(0, 1, borderCell(1, "") + borderCell(1, singleBorder("bottom")))
        + borderRow(1, 0, borderCell(1, "").repeat(2));

    var table = parseBorderTable(3, rows);

    assertTrue(table.middleBorder());
  }

  @Test
  void doesNotBorrowBordersFromDifferentColumnsAcrossOffsetRows() {
    String rows = borderRow(0, 1, borderCell(1, singleBorder("bottom")) + borderCell(1, ""))
        + borderRow(1, 0, borderCell(1, "") + borderCell(1, singleBorder("top")));

    var table = parseBorderTable(3, rows);

    assertFalse(table.middleBorder());
  }

  @Test
  void doesNotInventHeaderSeparatorWhenOffsetRowsHaveNoAdjacentCells() {
    String rows = borderRow(0, 1, borderCell(1, singleBorder("bottom")))
        + "<w:tr><w:trPr><w:gridBefore w:val=\"1\"/></w:trPr>"
        + "<w:tblPrEx><w:tblBorders>" + singleBorder("top") + "</w:tblBorders></w:tblPrEx>"
        + borderCell(1, "") + "</w:tr>";

    var table = parseBorderTable(2, rows);

    assertFalse(table.middleBorder());
  }

  @Test
  void doesNotTreatRowsWithoutRealCellsAsBorderEvidence() {
    String tableBorders = "<w:tblPr><w:tblBorders>" + singleBorder("top")
        + singleBorder("insideH") + singleBorder("bottom") + "</w:tblBorders></w:tblPr>";

    var table = parseBorderTable(2, tableBorders + "<w:tr/><w:tr/>");

    assertAll(() -> assertFalse(table.topBorder()), () -> assertFalse(table.middleBorder()),
        () -> assertFalse(table.bottomBorder()));
  }

  @Test
  void requiresBordersOnWrappedCellsEvenWithoutDeclaredGridColumns() {
    String borders = singleBorder("top") + singleBorder("bottom");
    String cells = borderCell(1, borders) + "<w:customXml>" + borderCell(1, "") + "</w:customXml>";

    var table = parseBorderTable(0, borderRow(0, 0, cells).repeat(2));

    assertAll(() -> assertFalse(table.topBorder()), () -> assertFalse(table.middleBorder()),
        () -> assertFalse(table.bottomBorder()));
  }

  @Test
  void combinesOnlyCorrespondingMergedCellEdgesWithoutOverridingExplicitNil() {
    String nilBottom = "<w:bottom w:val=\"nil\"/>";
    String nilTop = "<w:top w:val=\"nil\"/>";
    String upper = borderRow(1, 1, borderCell(2, singleBorder("bottom")) + borderCell(1, nilBottom));
    String lower = borderRow(1, 1, borderCell(1, nilTop).repeat(2) + borderCell(1, singleBorder("top")));
    String tableBorders = "<w:tblPr><w:tblBorders>" + singleBorder("insideH")
        + "</w:tblBorders></w:tblPr>";

    assertTrue(parseBorderTable(5, tableBorders + upper + lower).middleBorder());
    assertFalse(parseBorderTable(5, tableBorders + upper
        + lower.replace(singleBorder("top"), nilTop)).middleBorder());
  }

  @Test
  void requiresBordersOnExplicitEmptyCellsAlongsideOmittedSlots() {
    String borders = singleBorder("top") + singleBorder("bottom");
    String rows = borderRow(1, 1, borderCell(1, borders) + borderCell(1, "")).repeat(2);

    var table = parseBorderTable(4, rows);

    assertAll(() -> assertFalse(table.topBorder()), () -> assertFalse(table.middleBorder()),
        () -> assertFalse(table.bottomBorder()));
  }

  @Test
  void preservesExplicitNilOverridesOnMergedCellsBetweenOmittedSlots() {
    String rows = borderRow(1, 1,
        borderCell(2, "<w:top w:val=\"nil\"/><w:bottom w:val=\"nil\"/>")).repeat(2);
    String tableBorders = "<w:tblPr><w:tblBorders>" + singleBorder("top")
        + singleBorder("insideH") + singleBorder("bottom") + "</w:tblBorders></w:tblPr>";

    var table = parseBorderTable(4, tableBorders + rows);

    assertAll(() -> assertFalse(table.topBorder()), () -> assertFalse(table.middleBorder()),
        () -> assertFalse(table.bottomBorder()));
  }

  @Test
  void rejectsInvalidOccupancyNumbersEvenWhenAllRealCellsHaveBorders() {
    String borders = singleBorder("top") + singleBorder("bottom");
    String validRows = borderRow(1, 1, borderCell(2, borders)).repeat(2);
    for (String invalidRows : List.of(
        validRows.replace("gridBefore w:val=\"1\"", "gridBefore w:val=\"-1\""),
        validRows.replace("gridAfter w:val=\"1\"", "gridAfter w:val=\"2147483648\""),
        validRows.replace("gridSpan w:val=\"2\"", "gridSpan w:val=\"0\""))) {
      var table = parseBorderTable(4, invalidRows);

      assertFalse(table.xmlNumericValuesValid());
      assertAll(() -> assertFalse(table.topBorder()), () -> assertFalse(table.middleBorder()),
          () -> assertFalse(table.bottomBorder()));
    }
  }

  @Test
  void letsExplicitCellNilBordersOverrideTableBorders() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/><w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tblBorders></w:tblPr>"
        + "<w:tr><w:tc><w:tcPr><w:tcBorders><w:top w:val=\"nil\"/><w:bottom w:val=\"nil\"/></w:tcBorders></w:tcPr><w:p/></w:tc></w:tr>"
        + "<w:tr><w:tc><w:tcPr><w:tcBorders><w:top w:val=\"nil\"/><w:bottom w:val=\"nil\"/></w:tcBorders></w:tcPr><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertFalse(table.topBorder());
    assertFalse(table.middleBorder());
    assertFalse(table.bottomBorder());
  }

  @Test
  void letsExplicitCellBordersOverrideTableNilBorders() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tblPr><w:tblBorders><w:top w:val=\"nil\"/><w:insideH w:val=\"nil\"/><w:bottom w:val=\"nil\"/></w:tblBorders></w:tblPr>"
        + "<w:tr><w:tc><w:tcPr><w:tcBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tcBorders></w:tcPr><w:p/></w:tc></w:tr>"
        + "<w:tr><w:tc><w:tcPr><w:tcBorders><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tcBorders></w:tcPr><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertTrue(table.topBorder());
    assertTrue(table.middleBorder());
    assertTrue(table.bottomBorder());
  }

  @Test
  void doesNotUseNestedTableBordersForOuterTable() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:p/><w:tbl><w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/><w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/></w:tblBorders></w:tblPr>"
        + "<w:tr><w:tc><w:p/></w:tc></w:tr><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";

    var tables = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8));

    assertEquals(2, tables.size());
    assertFalse(tables.get(0).topBorder());
    assertTrue(tables.get(1).topBorder());
  }

  @Test
  void keepsCaptionWhenItAppearsAfterTableAndMarksOrder() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl>"
        + "<w:p><w:r><w:t>表1-2 表题在下方</w:t></w:r></w:p></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals("表1-2 表题在下方", table.captionText());
    assertFalse(table.captionBefore());
  }

  @Test
  void recognizesContinuationCaptionBeforeTable() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>续表4-7</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals("续表4-7", table.captionText());
    assertTrue(table.captionBefore());
  }

  @Test
  void recognizesAppendixTableCaption() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>表B2 接口定义</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals("表B2 接口定义", table.captionText());
  }

  @Test
  void recognizesCaptionAcrossWordBookmarkMarkers() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>表4-1 设备属性</w:t></w:r></w:p><w:bookmarkEnd w:id=\"2\"/>"
        + "<w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals("表4-1 设备属性", table.captionText());
    assertTrue(table.captionBefore());
  }

  @Test
  void doesNotAttachContinuationCaptionToFragmentBeforeCaption() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>video_url</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        + "<w:p><w:r><w:t>续表4-3</w:t></w:r></w:p>"
        + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>字段名</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";

    var tables = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8));

    assertNull(tables.get(0).captionText());
    assertEquals("续表4-3", tables.get(1).captionText());
    assertTrue(tables.get(1).captionBefore());
  }

  @Test
  void rejectsOutOfRangeAndInvalidTableGeometryIntegersInsteadOfNormalizingThem() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tblGrid><w:gridCol w:w=\"2147483648\"/></w:tblGrid>"
        + "<w:tr><w:trPr><w:gridBefore w:val=\"2147483648\"/><w:gridAfter w:val=\"-1\"/>"
        + "<w:trHeight w:val=\"2147483648\"/></w:trPr>"
        + "<w:tc><w:tcPr><w:gridSpan w:val=\"0\"/></w:tcPr><w:p/></w:tc></w:tr>"
        + "</w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertFalse(table.xmlNumericValuesValid());
    assertTrue(table.columnWidthsTwips().isEmpty());
    assertTrue(table.rowHeightsTwips().isEmpty());
  }

  @Test
  void rejectsOutOfRangeBorderSpacingWithoutIntegerOverflow() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tblPr><w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:space=\"4294967296\"/>"
        + "</w:tblBorders></w:tblPr><w:tr><w:tc><w:p/></w:tc></w:tr>"
        + "</w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertFalse(table.topBorder());
  }

  @Test
  void parsesStrictOoXmlWordprocessingNamespace() {
    String xml = "<w:document xmlns:w=\"http://purl.oclc.org/ooxml/wordprocessingml/main\"><w:body>"
        + "<w:p><w:r><w:t>Table 5-3 Strict</w:t></w:r></w:p><w:tbl><w:tblPr><w:tblBorders>"
        + "<w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/><w:insideH w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/><w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
        + "</w:tblBorders></w:tblPr><w:tr><w:tc><w:p/></w:tc></w:tr><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertTrue(table.topBorder() && table.middleBorder() && table.bottomBorder());
    assertNull(table.captionText());
  }

  @Test
  void readsCellStructureHeaderAndTableNote() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tblGrid><w:gridCol w:w=\"1200\"/><w:gridCol w:w=\"2400\"/></w:tblGrid>"
        + "<w:tr><w:trPr><w:trHeight w:val=\"360\"/></w:trPr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/></w:tcPr><w:p><w:r><w:t>数量（个）</w:t></w:r></w:p></w:tc></w:tr>"
        + "</w:tbl><w:p><w:r><w:t>注：数据来源于实验记录。</w:t></w:r></w:p></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertTrue(table.mergedCells());
    assertEquals(java.util.List.of(1200, 2400), table.columnWidthsTwips());
    assertEquals(java.util.List.of(360), table.rowHeightsTwips());
    assertEquals(java.util.List.of("数量（个）"), table.headerTexts());
    assertEquals("注：数据来源于实验记录。", table.noteText());
    assertFalse(table.repeatedPlaceholderText());
  }

  @Test
  void recordsOnlyRowsMarkedForRepeatInWordXml() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:trPr><w:tblHeader/></w:trPr><w:tc><w:p><w:r><w:t>列名</w:t></w:r></w:p></w:tc></w:tr>"
        + "<w:tr><w:trPr><w:tblHeader w:val=\"0\"/></w:trPr><w:tc><w:p><w:r><w:t>数据行</w:t></w:r></w:p></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p><w:r><w:t>未设置标记</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals(List.of(0), table.repeatingHeaderRowIndexes());
  }

  @Test
  void skipsTestCaseDescriptionRowWhenSelectingHeader() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"4\"/></w:tcPr><w:p><w:r><w:t>测试用例编号：TC-01；模块名称：用户登录</w:t></w:r></w:p></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p><w:r><w:t>编号</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>测试步骤</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>预期结果</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>实际结果</w:t></w:r></w:p></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p><w:r><w:t>1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>输入账号</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>登录成功</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>通过</w:t></w:r></w:p></w:tc></w:tr>"
        + "</w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals(List.of("编号", "测试步骤", "预期结果", "实际结果"), table.headerTexts());
    assertEquals(List.of(List.of("1", "输入账号", "登录成功", "通过")), table.dataRowTexts());
  }

  @Test
  void skipsPartiallySplitTestCaseDescriptionRowWhenSelectingHeader() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>娴嬭瘯鐢ㄤ緥缂栧彿锛歍C-02</w:t></w:r></w:p></w:tc>"
        + "<w:tc><w:p><w:r><w:t>妯″潡鍚嶇О锛氭敞鍐岃〃</w:t></w:r></w:p></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p><w:r><w:t>缂栧彿</w:t></w:r></w:p></w:tc>"
        + "<w:tc><w:p><w:r><w:t>娴嬭瘯姝ラ</w:t></w:r></w:p></w:tc>"
        + "<w:tc><w:p><w:r><w:t>缁撴灉</w:t></w:r></w:p></w:tc></w:tr>"
        + "</w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals(List.of("缂栧彿", "娴嬭瘯姝ラ", "缁撴灉"), table.headerTexts());
  }

  @Test
  void skipsFullWidthDescriptionCellsWhenEachCellHasALabel() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>测试用例编号：TC-03</w:t></w:r></w:p></w:tc>"
        + "<w:tc><w:p><w:r><w:t>模块名称：用户注册</w:t></w:r></w:p></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p><w:r><w:t>步骤</w:t></w:r></w:p></w:tc>"
        + "<w:tc><w:p><w:r><w:t>结果</w:t></w:r></w:p></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p><w:r><w:t>输入验证码</w:t></w:r></w:p></w:tc>"
        + "<w:tc><w:p><w:r><w:t>注册成功</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals(List.of("步骤", "结果"), table.headerTexts());
    assertEquals(List.of(List.of("输入验证码", "注册成功")), table.dataRowTexts());
  }

  @Test
  void locatesThreeLineMiddleBorderBelowHeaderAfterDescriptionRow() {
    String border = "<w:top w:val=\"single\" w:sz=\"4\"/><w:bottom w:val=\"single\" w:sz=\"4\"/>";
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tblGrid><w:gridCol w:w=\"1000\"/><w:gridCol w:w=\"1000\"/></w:tblGrid>"
        + "<w:tblPr><w:tblBorders>" + border + "</w:tblBorders></w:tblPr>"
        + "<w:tr><w:tc><w:tcPr><w:gridSpan w:val=\"2\"/></w:tcPr>"
        + "<w:p><w:r><w:t>测试用例编号：TC-01</w:t></w:r></w:p></w:tc></w:tr>"
        + "<w:tr><w:tc><w:tcPr><w:tcBorders><w:bottom w:val=\"single\" w:sz=\"4\"/>"
        + "</w:tcBorders></w:tcPr><w:p><w:r><w:t>项目</w:t></w:r></w:p></w:tc>"
        + "<w:tc><w:tcPr><w:tcBorders><w:bottom w:val=\"single\" w:sz=\"4\"/>"
        + "</w:tcBorders></w:tcPr><w:p><w:r><w:t>结果</w:t></w:r></w:p></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p><w:r><w:t>登录</w:t></w:r></w:p></w:tc>"
        + "<w:tc><w:p><w:r><w:t>通过</w:t></w:r></w:p></w:tc></w:tr>"
        + "</w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals(List.of("项目", "结果"), table.headerTexts());
    assertTrue(table.topBorder());
    assertTrue(table.middleBorder());
    assertTrue(table.bottomBorder());
  }

  @Test
  void keepsFirstRowAsHeaderForOrdinaryParameterTable() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>温度（℃）</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>时间（s）</w:t></w:r></w:p></w:tc></w:tr>"
        + "<w:tr><w:tc><w:p><w:r><w:t>25</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>10</w:t></w:r></w:p></w:tc></w:tr>"
        + "</w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals(List.of("温度（℃）", "时间（s）"), table.headerTexts());
    assertEquals(List.of(List.of("25", "10")), table.dataRowTexts());
  }

  @Test
  void recognizesTableNoteAfterWordBookmarkMarker() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl><w:bookmarkEnd w:id=\"4\"/>"
        + "<w:p><w:r><w:t>注：单位为毫米。</w:t></w:r></w:p></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals("注：单位为毫米。", table.noteText());
  }

  @Test
  void recognizesNumberedTableNote() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl>"
        + "<w:p><w:r><w:t>注1：单位为毫米。</w:t></w:r></w:p></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals("注1：单位为毫米。", table.noteText());
  }

  private static TableModel parseBorderTable(int columns, String rows) {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tblGrid>" + "<w:gridCol w:w=\"1000\"/>".repeat(columns) + "</w:tblGrid>"
        + rows + "</w:tbl></w:body></w:document>";
    return new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);
  }

  private static String borderRow(int before, int after, String cells) {
    return "<w:tr><w:trPr><w:gridBefore w:val=\"" + before + "\"/><w:gridAfter w:val=\""
        + after + "\"/></w:trPr>" + cells + "</w:tr>";
  }

  private static String borderCell(int span, String borders) {
    return "<w:tc><w:tcPr><w:gridSpan w:val=\"" + span + "\"/><w:tcBorders>" + borders
        + "</w:tcBorders></w:tcPr><w:p/></w:tc>";
  }

  private static String singleBorder(String side) {
    return "<w:" + side + " w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>";
  }
}
