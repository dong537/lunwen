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
}
