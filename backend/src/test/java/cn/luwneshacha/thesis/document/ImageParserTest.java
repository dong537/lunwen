package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
  void acceptsTheLargestPositiveSignedLongImageDimension() {
    String xml = imageDocument("9223372036854775807");

    var image = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals(Long.MAX_VALUE, image.widthEmu());
    assertFalse(image.sizeEvidenceInvalid());
  }

  @Test
  void rejectsImageDimensionThatExceedsTheModelRange() {
    String xml = imageDocument("9223372036854775808");

    var image = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals(0L, image.widthEmu());
    assertTrue(image.sizeEvidenceInvalid());
  }

  private String imageDocument(String width) {
    return "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
        + "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"" + width + "\" cy=\"1\"/>"
        + "<a:graphic><pic:pic/></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        + "</w:body></w:document>";
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
  void preservesCaptionWhitespaceForExactRuleValidation() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
        + "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"100\" cy=\"200\"/><a:graphic><pic:pic/></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        + "<w:p><w:r><w:t xml:space=\"preserve\"> 图3-1　标题 </w:t></w:r></w:p></w:body></w:document>";

    var image = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals(" 图3-1　标题 ", image.captionText());
  }

  @Test
  void recognizesCaptionAfterAnEmptyParagraphAndBookmarkMarker() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
        + "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"100\" cy=\"200\"/><a:graphic><pic:pic/></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        + "<w:p/><w:bookmarkEnd w:id=\"3\"/><w:p><w:r><w:t>图3-1 系统结构图</w:t></w:r></w:p>"
        + "</w:body></w:document>";

    var image = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals("图3-1 系统结构图", image.captionText());
  }

  @Test
  void recognizesCaptionAfterIdeographicSpaceParagraph() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
        + "<w:p><w:r><w:drawing><wp:inline><a:graphic><pic:pic/></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        + "<w:p><w:r><w:t>　</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>图3-1 系统结构图</w:t></w:r></w:p>"
        + "</w:body></w:document>";

    var image = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals("图3-1 系统结构图", image.captionText());
  }

  @Test
  void recognizesCaptionWithNonBreakingSpaceBeforeFigureNumber() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
        + "<w:p><w:r><w:drawing><wp:inline><a:graphic><pic:pic/></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        + "<w:p><w:r><w:t xml:space=\"preserve\">\u00A0图3-1 系统结构图</w:t></w:r></w:p>"
        + "</w:body></w:document>";

    var image = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);

    assertEquals("\u00A0图3-1 系统结构图", image.captionText());
  }

  @Test
  void doesNotLetAnEarlierImageClaimCaptionPastAnotherImage() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
        + "<w:p><w:r><w:drawing><wp:inline><a:graphic><pic:pic/></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        + "<w:p><w:r><w:drawing><wp:inline><a:graphic><pic:pic/></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        + "<w:p><w:r><w:t>图3-1 第二张图</w:t></w:r></w:p>"
        + "</w:body></w:document>";

    var images = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8));

    assertEquals(2, images.size());
    assertNull(images.get(0).captionText());
    assertEquals("图3-1 第二张图", images.get(1).captionText());
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
  void ignoresVmlPreviewOwnedByEmbeddedEquationObject() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o=\"urn:schemas-microsoft-com:office:office\" "
        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>"
        + "<w:p><w:r><w:object><w:pict><v:shape id=\"_x0000_i1025\"><v:imagedata r:id=\"rIdPreview\"/>"
        + "</v:shape></w:pict><o:OLEObject Type=\"Embed\" ProgID=\"Equation.3\" ShapeID=\"_x0000_i1025\" r:id=\"rIdOle\"/>"
        + "</w:object></w:r></w:p></w:body></w:document>";

    var images = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8));

    assertTrue(images.isEmpty());
  }

  @Test
  void ignoresDrawingMlPreviewOwnedByMathTypeOleObject() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\" "
        + "xmlns:o=\"urn:schemas-microsoft-com:office:office\" "
        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>"
        + "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"100\" cy=\"200\"/><a:graphic><a:graphicData>"
        + "<pic:pic><pic:blipFill><a:blip r:embed=\"rIdPreview\"/></pic:blipFill></pic:pic>"
        + "<o:OLEObject ProgID=\"MathType.MTEF.7\" r:id=\"rIdOle\"/>"
        + "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        + "</w:body></w:document>";

    var images = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8));

    assertTrue(images.isEmpty());
  }

  @Test
  void keepsPreviewImageForNonEquationOleObject() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o=\"urn:schemas-microsoft-com:office:office\" "
        + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>"
        + "<w:p><w:r><w:object><w:pict><v:shape id=\"shape1\"><v:imagedata r:id=\"rIdPreview\"/>"
        + "</v:shape></w:pict><o:OLEObject Type=\"Embed\" ProgID=\"Excel.Sheet.12\" ShapeID=\"shape1\" r:id=\"rIdOle\"/>"
        + "</w:object></w:r></w:p></w:body></w:document>";

    var images = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8));

    assertEquals(1, images.size());
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

  @Test
  void preservesExplicitInvalidDrawingSizeAsInvalidEvidenceInsteadOfZero() throws Exception {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><w:body>"
        + "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"not-a-number\" cy=\"-1\"/>"
        + "<a:graphic><pic:pic/></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        + "</w:body></w:document>";

    var image = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8)).get(0);
    var serializedImage = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(image);

    assertTrue(serializedImage.path("sizeEvidenceInvalid").asBoolean());
  }

  @Test
  void parsesNativeChartsAndDoesNotCountTheirFallbackPictureTwice() {
    String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
        + "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" "
        + "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" "
        + "xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\" "
        + "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\" "
        + "xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\"><w:body>"
        + "<w:p><w:r><mc:AlternateContent><mc:Choice Requires=\"c\"><w:drawing><wp:inline>"
        + "<wp:extent cx=\"914400\" cy=\"457200\"/><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart r:id=\"rId1\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/>"
        + "</a:graphicData></a:graphic></wp:inline></w:drawing></mc:Choice>"
        + "<mc:Fallback><w:drawing><wp:inline><wp:extent cx=\"914400\" cy=\"457200\"/>"
        + "<a:graphic><pic:pic/></a:graphic></wp:inline></w:drawing></mc:Fallback>"
        + "</mc:AlternateContent></w:r></w:p>"
        + "<w:p><w:r><w:t>图1-1 原生图表</w:t></w:r></w:p>"
        + "<w:p><w:r><w:drawing><wp:anchor><wp:extent cx=\"1828800\" cy=\"914400\"/>"
        + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart/></a:graphicData>"
        + "</a:graphic></wp:anchor></w:drawing></w:r></w:p>"
        + "<w:p><w:r><w:t>图1-2 锚定图表</w:t></w:r></w:p></w:body></w:document>";

    var images = new ImageParser().parse(xml.getBytes(StandardCharsets.UTF_8));

    assertEquals(2, images.size());
    assertEquals(914400L, images.get(0).widthEmu());
    assertEquals(457200L, images.get(0).heightEmu());
    assertTrue(images.get(0).inline());
    assertEquals("图1-1 原生图表", images.get(0).captionText());
    assertEquals(java.util.List.of(0, 0), images.get(0).source().elementPath());
    assertEquals(java.util.List.of(0, 1), images.get(0).captionSource().elementPath());
    assertEquals(1828800L, images.get(1).widthEmu());
    assertEquals(914400L, images.get(1).heightEmu());
    assertFalse(images.get(1).inline());
    assertEquals("图1-2 锚定图表", images.get(1).captionText());
    assertEquals(java.util.List.of(0, 2), images.get(1).source().elementPath());
    assertEquals(java.util.List.of(0, 3), images.get(1).captionSource().elementPath());
  }
}
