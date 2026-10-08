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
}
