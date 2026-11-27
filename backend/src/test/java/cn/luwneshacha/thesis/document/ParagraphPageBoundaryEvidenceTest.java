package cn.luwneshacha.thesis.document;

import static cn.luwneshacha.thesis.document.ParagraphPageBoundaryEvidence.Boundary.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParagraphPageBoundaryEvidenceTest {
  @Test
  void distinguishesBreakBeforeAndAfterCoverText() throws Exception {
    assertEquals(new ParagraphPageBoundaryEvidence(PRESENT, PRESENT), parse(
        "<w:r><w:br w:type='page'/><w:t>封面</w:t></w:r>", ""));
    assertEquals(new ParagraphPageBoundaryEvidence(ABSENT, PRESENT), parse(
        "<w:r><w:t>封面</w:t><w:br w:type='page'/></w:r>", ""));
  }

  @Test
  void renderedBreakIsOnlyUncertainEvidence() throws Exception {
    assertEquals(new ParagraphPageBoundaryEvidence(UNKNOWN, UNKNOWN), parse(
        "<w:r><w:lastRenderedPageBreak/><w:t>封面</w:t></w:r>", ""));
  }

  @Test
  void directFalseOverridesInheritedPageBreak() throws Exception {
    String style = "<w:style w:styleId='h'><w:pPr><w:pageBreakBefore/></w:pPr></w:style>";
    assertEquals(new ParagraphPageBoundaryEvidence(PRESENT, PRESENT), parse(
        "<w:pPr><w:pStyle w:val='h'/></w:pPr><w:r><w:t>封面</w:t></w:r>", style));
    assertEquals(new ParagraphPageBoundaryEvidence(ABSENT, ABSENT), parse(
        "<w:pPr><w:pStyle w:val='h'/><w:pageBreakBefore w:val='false'/></w:pPr>"
            + "<w:r><w:t>封面</w:t></w:r>", style));
  }

  @ParameterizedTest
  @ValueSource(strings = {"<w:del><w:r><w:br w:type='page'/></w:r></w:del>",
      "<w:r><w:pict><w:txbxContent><w:p><w:r><w:br w:type='page'/></w:r></w:p></w:txbxContent></w:pict></w:r>"})
  void deletedOrTextBoxBreaksDoNotChangeMainStoryBoundary(String ignored) throws Exception {
    assertEquals(new ParagraphPageBoundaryEvidence(ABSENT, ABSENT), parse(ignored + "<w:r><w:t>封面</w:t></w:r>", ""));
  }

  @Test
  void missingStyleIsNotAbsenceOfPagination() throws Exception {
    assertEquals(new ParagraphPageBoundaryEvidence(UNKNOWN, UNKNOWN), parse(
        "<w:pPr><w:pStyle w:val='missing'/></w:pPr><w:r><w:t>封面</w:t></w:r>", ""));
  }

  @Test
  void unresolvedAlternativeInLaterBodyDoesNotContaminateCoverPagination() throws Exception {
    String w = XmlPartSupport.W;
    var document = XmlPartSupport.parse(("<w:document xmlns:w='" + w + "' "
        + "xmlns:mc='http://schemas.openxmlformats.org/markup-compatibility/2006' xmlns:x='urn:unresolved'>"
        + "<w:body><w:p><w:r><w:t>封面</w:t></w:r></w:p><w:p><mc:AlternateContent>"
        + "<mc:Choice Requires='x'><x:content/></mc:Choice><mc:Fallback><w:r><w:t>正文图形</w:t>"
        + "</w:r></mc:Fallback></mc:AlternateContent></w:p></w:body></w:document>")
        .getBytes(StandardCharsets.UTF_8));
    var cover = (org.w3c.dom.Element) document.getElementsByTagNameNS(w, "p").item(0);
    assertEquals(new ParagraphPageBoundaryEvidence(ABSENT, ABSENT), ParagraphPageBoundaryEvidence.parse(cover, null));
  }

  private ParagraphPageBoundaryEvidence parse(String content, String styles) throws Exception {
    String w = XmlPartSupport.W;
    var paragraph = XmlPartSupport.parse(("<w:p xmlns:w='" + w + "'>" + content + "</w:p>")
        .getBytes(StandardCharsets.UTF_8)).getDocumentElement();
    var styleRoot = XmlPartSupport.parse(("<w:styles xmlns:w='" + w + "'>" + styles + "</w:styles>")
        .getBytes(StandardCharsets.UTF_8)).getDocumentElement();
    return ParagraphPageBoundaryEvidence.parse(paragraph, styleRoot);
  }
}
