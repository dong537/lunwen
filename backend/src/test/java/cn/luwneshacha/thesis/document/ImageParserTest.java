package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ImageParserTest {
  @Test
  void readsDrawingSizeAndFollowingFigureCaption() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
        + "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"100\" cy=\"200\"/><a:graphic><pic:pic><pic:blipFill><a:blip/></pic:blipFill></pic:pic></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        + "<w:p><w:r><w:t>图3-1 系统结构图</w:t></w:r></w:p></w:body></w:document>";

    var images = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8));

    assertEquals(1, images.size());
    assertEquals("图3-1 系统结构图", images.get(0).captionText());
    assertEquals(100L, images.get(0).widthEmu());
    assertEquals(200L, images.get(0).heightEmu());
    assertEquals(java.util.List.of(0, 0), images.get(0).source().elementPath());
    assertFalse(images.get(0).inline() == false);
  }

  @Test
  void recognizesFigureCaptionWhenNumberAndTitleHaveNoSeparatingSpace() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
        + "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"100\" cy=\"200\"/><a:graphic><pic:pic><pic:blipFill><a:blip/></pic:blipFill></pic:pic></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        + "<w:p><w:r><w:t>图3-4系统结构图</w:t></w:r></w:p></w:body></w:document>";

    var image = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals("图3-4系统结构图", image.captionText());
  }

  @Test
  void recognizesAppendixFigureCaption() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
        + "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"100\" cy=\"200\"/><a:graphic><pic:pic/></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        + "<w:p><w:r><w:t>图A1 系统源代码结构</w:t></w:r></w:p></w:body></w:document>";

    var image = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals("图A1 系统源代码结构", image.captionText());
  }

  @Test
  void treatsInlineImageAndCaptionAsVerifiedWhenBothParagraphsAreCentered() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
        + "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing><wp:inline><wp:extent cx=\"100\" cy=\"200\"/><a:graphic><pic:pic><pic:blipFill><a:blip/></pic:blipFill></pic:pic></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        + "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:t>图3-1 系统结构图</w:t></w:r></w:p>"
        + "</w:body></w:document>";

    var image = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertFalse(image.placementNeedsReview());
  }

  @Test
  void resolvesCenterAlignmentThroughParagraphStyleInheritance() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
        + "<w:p><w:pPr><w:pStyle w:val=\"Figure\"/></w:pPr><w:r><w:drawing><wp:inline><wp:extent cx=\"100\" cy=\"200\"/><a:graphic><pic:pic><pic:blipFill><a:blip/></pic:blipFill></pic:pic></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        + "<w:p><w:pPr><w:pStyle w:val=\"Caption\"/></w:pPr><w:r><w:t>图3-1 系统结构图</w:t></w:r></w:p>"
        + "</w:body></w:document>";
    String styles = "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Centered\"><w:pPr><w:jc w:val=\"center\"/></w:pPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Figure\"><w:basedOn w:val=\"Centered\"/></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Caption\"><w:basedOn w:val=\"Figure\"/></w:style>"
        + "</w:styles>";

    var image = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8),
        styles.getBytes(StandardCharsets.UTF_8)).get(0);

    assertFalse(image.placementNeedsReview());
  }

  @Test
  void ignoresGroupedDrawingThatIsNotAnImage() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><w:body>"
        + "<w:p><w:r><w:drawing><wp:anchor><a:graphic><a:graphicData uri=\"http://schemas.microsoft.com/office/word/2010/wordprocessingGroup\"/></a:graphic></wp:anchor></w:drawing></w:r></w:p>"
        + "</w:body></w:document>";

    var images = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8));

    assertEquals(0, images.size());
  }

  @Test
  void recognizesLegacyVmlPictureAndMarksItsPlacementForReview() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o=\"urn:schemas-microsoft-com:office:office\" "
        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>"
        + "<w:p><w:r><w:pict><v:shape style=\"width:100pt;height:50pt\"><v:imagedata r:id=\"rId1\" o:title=\"\"/>"
        + "</v:shape></w:pict></w:r></w:p><w:p><w:r><w:t>图1-1 旧式图片</w:t></w:r></w:p>"
        + "</w:body></w:document>";

    var images = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8));

    assertEquals(1, images.size());
    assertEquals("图1-1 旧式图片", images.get(0).captionText());
    assertTrue(images.get(0).placementNeedsReview());
  }

  @Test
  void recognizesStrictOoXmlDrawingPicture() {
    String xml = "<w:document xmlns:w=\"http://purl.oclc.org/ooxml/wordprocessingml/main\" "
        + "xmlns:a=\"http://purl.oclc.org/ooxml/drawingml/main\" "
        + "xmlns:pic=\"http://purl.oclc.org/ooxml/drawingml/picture\"><w:body>"
        + "<w:p><w:r><w:drawing><a:graphic><a:graphicData><pic:pic/></a:graphicData>"
        + "</a:graphic></w:drawing></w:r></w:p></w:body></w:document>";

    var images = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8));

    assertEquals(1, images.size());
  }

  @Test
  void readsDrawingTransformSizeForAspectRatioChecks() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
        + "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"100\" cy=\"200\"/><a:graphic><pic:pic>"
        + "<pic:spPr><a:xfrm><a:ext cx=\"200\" cy=\"200\"/></a:xfrm></pic:spPr>"
        + "</pic:pic></a:graphic></wp:inline></w:drawing></w:r></w:p></w:body></w:document>";

    var image = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals(200L, image.transformWidthEmu());
    assertEquals(200L, image.transformHeightEmu());
  }
}
