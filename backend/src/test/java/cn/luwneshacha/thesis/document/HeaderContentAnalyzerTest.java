package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;

class HeaderContentAnalyzerTest {
  private static final String W = XmlPartSupport.W;
  private static final String FIELD = "<w:r><w:fldChar w:fldCharType='begin'/></w:r>"
      + "<w:r><w:instrText> DATE </w:instrText></w:r>"
      + "<w:r><w:fldChar w:fldCharType='separate'/></w:r>"
      + "<w:r><w:t>2026年11月</w:t></w:r>"
      + "<w:r><w:fldChar w:fldCharType='end'/></w:r>";

  @Test
  void complexFieldCachedTextDoesNotProveAVisibleCoverHeader() throws Exception {
    assertEquals(HeaderModel.DisplayContent.UNKNOWN, analyze("<w:p>" + FIELD + "</w:p>", ""));
  }

  @Test
  void literalTextAfterACompleteFieldStillProvesHeaderContent() throws Exception {
    assertEquals(HeaderModel.DisplayContent.PRESENT, analyze("<w:p>" + FIELD
        + "<w:r><w:t>学校页眉</w:t></w:r></w:p>", ""));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "<w:tbl><w:tblPr><w:tblBorders><w:top w:val='single'/></w:tblBorders></w:tblPr>"
          + "<w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl>",
      "<x:content xmlns:x='urn:unresolved-header-content'/>",
      "<w:p><w:r><w:noBreakHyphen/></w:r></w:p>"})
  void unresolvedObjectsCannotCertifyAnEmptyHeader(String content) throws Exception {
    assertEquals(HeaderModel.DisplayContent.UNKNOWN, analyze(content, ""));
  }

  @ParameterizedTest
  @ValueSource(strings = {"",
      "<w:style w:type='paragraph' w:styleId='header'><w:basedOn w:val='header'/></w:style>"})
  void missingOrCyclicStyleCannotCertifyVisibility(String styles) throws Exception {
    assertEquals(HeaderModel.DisplayContent.UNKNOWN, analyze(
        "<w:p><w:pPr><w:pStyle w:val='header'/></w:pPr><w:r><w:t>文字</w:t></w:r></w:p>", styles));
  }

  @Test
  void explicitNoBorderOverridesInheritedHeaderBorder() throws Exception {
    assertEquals(HeaderModel.DisplayContent.EMPTY, analyze(
        "<w:p><w:pPr><w:pStyle w:val='header'/><w:pBdr><w:bottom w:val='none'/>"
            + "</w:pBdr></w:pPr></w:p>",
        "<w:style w:type='paragraph' w:styleId='header'><w:pPr><w:pBdr>"
            + "<w:bottom w:val='single'/></w:pBdr></w:pPr></w:style>"));
  }

  private HeaderModel.DisplayContent analyze(String content, String styles) throws Exception {
    Element header = xml("<w:hdr xmlns:w='" + W + "'>" + content + "</w:hdr>");
    Element styleRoot = xml("<w:styles xmlns:w='" + W + "'>" + styles + "</w:styles>");
    return new HeaderContentAnalyzer(styleRoot).analyze(header);
  }

  private Element xml(String value) throws Exception {
    return XmlPartSupport.parse(value.getBytes(StandardCharsets.UTF_8)).getDocumentElement();
  }
}
