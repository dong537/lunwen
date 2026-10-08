package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TableParserTest {
  @Test
  void linksCaptionBeforeTableAndReadsThreeLineEvidence() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:t>表1-1 测试数据</w:t></w:r></w:p><w:tbl><w:tblPr><w:tblBorders>"
        + "<w:top w:val=\"single\"/><w:insideH w:val=\"single\"/><w:bottom w:val=\"single\"/></w:tblBorders></w:tblPr>"
        + "<w:tr><w:tc><w:p/></w:tc></w:tr><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";
    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);
    assertEquals("表1-1 测试数据", table.captionText());
    assertTrue(table.captionBefore());
    assertFalse(table.layoutNeedsReview());
    assertTrue(table.topBorder() && table.middleBorder() && table.bottomBorder());
    assertFalse(table.verticalBorders());
  }

  @Test
  void readsThreeLineEvidenceStoredOnCellsWhenTableBordersAreAbsent() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:tcPr><w:tcBorders><w:top w:val=\"single\"/><w:insideH w:val=\"single\"/></w:tcBorders></w:tcPr><w:p/></w:tc></w:tr>"
        + "<w:tr><w:tc><w:tcPr><w:tcBorders><w:bottom w:val=\"single\"/></w:tcBorders></w:tcPr><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertTrue(table.topBorder() && table.middleBorder() && table.bottomBorder());
    assertFalse(table.verticalBorders());
  }

  @Test
  void doesNotTreatMisplacedCellBordersAsOuterTableEdges() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:tcPr><w:tcBorders><w:bottom w:val=\"single\"/></w:tcBorders></w:tcPr><w:p/></w:tc></w:tr>"
        + "<w:tr><w:tc><w:tcPr><w:tcBorders><w:top w:val=\"single\"/></w:tcBorders></w:tcPr><w:p/></w:tc></w:tr></w:tbl>"
        + "</w:body></w:document>";

    var table = new TableParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertFalse(table.topBorder());
    assertTrue(table.middleBorder());
    assertFalse(table.bottomBorder());
  }

  @Test
  void doesNotUseNestedTableBordersForOuterTable() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
        + "<w:tbl><w:tr><w:tc><w:p/><w:tbl><w:tblPr><w:tblBorders><w:top w:val=\"single\"/><w:insideH w:val=\"single\"/><w:bottom w:val=\"single\"/></w:tblBorders></w:tblPr>"
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
  void parsesStrictOoXmlWordprocessingNamespace() {
    String xml = "<w:document xmlns:w=\"http://purl.oclc.org/ooxml/wordprocessingml/main\"><w:body>"
        + "<w:p><w:r><w:t>Table 5-3 Strict</w:t></w:r></w:p><w:tbl><w:tblPr><w:tblBorders>"
        + "<w:top w:val=\"single\"/><w:insideH w:val=\"single\"/><w:bottom w:val=\"single\"/>"
        + "</w:tblBorders></w:tblPr><w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl></w:body></w:document>";

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
}
