package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class XmlPartSupportTest {
  private static final String START = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
      + "xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\" "
      + "xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" "
      + "xmlns:w14=\"http://schemas.microsoft.com/office/word/2010/wordml\" "
      + "xmlns:v=\"urn:schemas-microsoft-com:vml\"><w:body>";
  private static final String END = "</w:body></w:document>";

  @Test
  void usesOnlyFirstSupportedChoiceRatherThanConcatenatingAlternatives() throws Exception {
    var document = XmlPartSupport.parse((START + "<w:p><mc:AlternateContent>"
        + "<mc:Choice Requires=\"m\"><w:r><w:t>first</w:t></w:r></mc:Choice>"
        + "<mc:Choice Requires=\"w\"><w:r><w:t>second</w:t></w:r></mc:Choice>"
        + "<mc:Fallback><w:r><w:t>fallback</w:t></w:r></mc:Fallback>"
        + "</mc:AlternateContent></w:p>" + END).getBytes(StandardCharsets.UTF_8));

    assertEquals("first", XmlPartSupport.text(document.getDocumentElement()));
    assertTrue(XmlPartSupport.compatibilityGaps(document).isEmpty());
  }

  @Test
  void unknownEarlierChoiceDoesNotMasqueradeAsCertainLaterChoice() throws Exception {
    var document = parse("<w:p><mc:AlternateContent>"
        + "<mc:Choice Requires=\"w14\"><w:r><w:t>Word 2010</w:t></w:r></mc:Choice>"
        + "<mc:Choice Requires=\"m\"><w:r><w:t>known</w:t></w:r></mc:Choice>"
        + "<mc:Fallback><w:r><w:t>backup</w:t></w:r></mc:Fallback>"
        + "</mc:AlternateContent></w:p>");

    assertEquals("", XmlPartSupport.text(document.getDocumentElement()));
    assertEquals(List.of(0, 0, 0), XmlPartSupport.compatibilityGaps(document).get(0).elementPath());
    assertFalse(XmlPartSupport.compatibilityGaps(document).get(0).reason().isBlank());
  }

  @Test
  void unknownChoiceSuppressesFallbackWithoutMovingLaterSourcePaths() throws Exception {
    var document = parse("<w:p><mc:AlternateContent>"
        + "<mc:Choice Requires=\"w14\"><w:r><w:t>unselected</w:t></w:r></mc:Choice>"
        + "<mc:Fallback><w:r><w:t>fallback</w:t></w:r></mc:Fallback>"
        + "</mc:AlternateContent><w:r><w:t>after</w:t></w:r></w:p>");
    var texts = document.getElementsByTagNameNS(XmlPartSupport.W, "t");

    assertEquals("after", XmlPartSupport.text(document.getDocumentElement()));
    assertEquals(1, texts.getLength());
    assertEquals(List.of(0, 0, 1, 0), pathOf((Element) texts.item(0)));
    assertEquals(1, XmlPartSupport.compatibilityGaps(document).size());
  }

  @Test
  void selectedChoiceKeepsItsOriginalPathWhenOtherBranchesAreSuppressed() throws Exception {
    var document = parse("<w:p><mc:AlternateContent>"
        + "<mc:Choice Requires=\"m\"><w:r><w:t>chosen</w:t></w:r></mc:Choice>"
        + "<mc:Choice Requires=\"w\"><w:r><w:t>unused</w:t></w:r></mc:Choice>"
        + "<mc:Fallback><w:r><w:t>backup</w:t></w:r></mc:Fallback>"
        + "</mc:AlternateContent><w:r><w:t>after</w:t></w:r></w:p>");
    var texts = document.getElementsByTagNameNS(XmlPartSupport.W, "t");

    assertEquals("chosenafter", XmlPartSupport.text(document.getDocumentElement()));
    assertEquals(List.of(0, 0, 0, 0, 0, 0), pathOf((Element) texts.item(0)));
    assertEquals(List.of(0, 0, 1, 0), pathOf((Element) texts.item(1)));
  }

  @Test
  void onlyFallbackIsSuppressedAndMarkedStructurallyUncertain() throws Exception {
    var document = parse("<w:p><mc:AlternateContent>"
        + "<mc:Fallback><w:r><w:t>backup</w:t></w:r></mc:Fallback>"
        + "</mc:AlternateContent></w:p>");

    assertEquals("", XmlPartSupport.text(document.getDocumentElement()));
    assertEquals(1, XmlPartSupport.compatibilityGaps(document).size());
  }

  @Test
  void missingFallbackAndUnknownChoiceLeavesNoVisibleCandidate() throws Exception {
    var document = parse("<w:p><mc:AlternateContent>"
        + "<mc:Choice Requires=\"w14\"><w:r><w:t>unknown</w:t></w:r></mc:Choice>"
        + "</mc:AlternateContent></w:p>");

    assertEquals("", XmlPartSupport.text(document.getDocumentElement()));
    assertEquals(1, XmlPartSupport.compatibilityGaps(document).size());
  }

  @Test
  void selectsNestedAlternativesWithinOnlyTheActiveOuterBranch() throws Exception {
    var document = parse("<w:p><mc:AlternateContent>"
        + "<mc:Choice Requires=\"m\"><mc:AlternateContent>"
        + "<mc:Choice Requires=\"w\"><w:r><w:t>nested</w:t></w:r></mc:Choice>"
        + "<mc:Fallback><w:r><w:t>nested backup</w:t></w:r></mc:Fallback>"
        + "</mc:AlternateContent></mc:Choice>"
        + "<mc:Fallback><mc:AlternateContent><mc:Fallback><w:r><w:t>inactive</w:t></w:r>"
        + "</mc:Fallback></mc:AlternateContent></mc:Fallback>"
        + "</mc:AlternateContent></w:p>");

    assertEquals("nested", XmlPartSupport.text(document.getDocumentElement()));
    assertTrue(XmlPartSupport.compatibilityGaps(document).isEmpty());
  }

  @Test
  void recognizesVmlChoiceUsedByExistingWordExports() throws Exception {
    var document = parse("<w:p><mc:AlternateContent>"
        + "<mc:Choice Requires=\"v\"><w:r><w:t>current</w:t></w:r></mc:Choice>"
        + "<mc:Fallback><w:r><w:t>old</w:t></w:r></mc:Fallback>"
        + "</mc:AlternateContent></w:p>");

    assertEquals("current", XmlPartSupport.text(document.getDocumentElement()));
    assertTrue(XmlPartSupport.compatibilityGaps(document).isEmpty());
  }

  @Test
  void doesNotReportAlternativesInsideDeletedContent() throws Exception {
    var document = parse("<w:p><w:del><mc:AlternateContent>"
        + "<mc:Choice Requires=\"w14\"><w:r><w:t>deleted</w:t></w:r></mc:Choice>"
        + "</mc:AlternateContent></w:del><w:moveFrom><mc:AlternateContent>"
        + "<mc:Choice Requires=\"w14\"><w:r><w:t>moved out</w:t></w:r></mc:Choice>"
        + "</mc:AlternateContent></w:moveFrom></w:p>");

    assertTrue(XmlPartSupport.compatibilityGaps(document).isEmpty());
  }

  @Test
  void unboundRequiresPrefixSuppressesFallbackAndReportsUncertainty() throws Exception {
    var document = parse("<w:p><mc:AlternateContent>"
        + "<mc:Choice Requires=\"unbound\"><w:r><w:t>unusable</w:t></w:r></mc:Choice>"
        + "<mc:Fallback><w:r><w:t>fallback</w:t></w:r></mc:Fallback>"
        + "</mc:AlternateContent></w:p>");

    assertEquals("", XmlPartSupport.text(document.getDocumentElement()));
    assertEquals(1, XmlPartSupport.compatibilityGaps(document).size());
  }

  @Test
  void malformedBranchOrderSuppressesAllCandidates() throws Exception {
    var document = parse("<w:p><mc:AlternateContent>"
        + "<mc:Choice Requires=\"m\"><w:r><w:t>candidate</w:t></w:r></mc:Choice>"
        + "<mc:Fallback><w:r><w:t>backup</w:t></w:r></mc:Fallback>"
        + "<mc:Choice Requires=\"w\"><w:r><w:t>late</w:t></w:r></mc:Choice>"
        + "</mc:AlternateContent></w:p>");

    assertEquals("", XmlPartSupport.text(document.getDocumentElement()));
    assertEquals(1, XmlPartSupport.compatibilityGaps(document).size());
  }

  private static org.w3c.dom.Document parse(String body) throws Exception {
    return XmlPartSupport.parse((START + body + END).getBytes(StandardCharsets.UTF_8));
  }

  private static List<Integer> pathOf(Element element) {
    var reverse = new ArrayList<Integer>();
    for (Node node = element; node.getParentNode() instanceof Element parent; node = parent) {
      reverse.add(XmlPartSupport.children(parent).indexOf(node));
    }
    return reverse.reversed();
  }
}
