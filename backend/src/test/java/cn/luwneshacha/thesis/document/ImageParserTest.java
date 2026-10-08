package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ImageParserTest {
  @Test
  void readsDrawingSizeAndFollowingFigureCaption() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"100\" cy=\"200\"/><a:graphic/></wp:inline></w:drawing></w:r></w:p>"
        + "<w:p><w:r><w:t>图3-1 系统结构图</w:t></w:r></w:p></w:body></w:document>";

    var images = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8));

    assertEquals(1, images.size());
    assertEquals("图3-1 系统结构图", images.get(0).captionText());
    assertEquals(100L, images.get(0).widthEmu());
    assertEquals(200L, images.get(0).heightEmu());
    assertFalse(images.get(0).inline() == false);
  }
}
