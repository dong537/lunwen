package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.*;

import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.Applicability;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.ExecutionStatus;
import cn.luwneshacha.thesis.rules.Judgement;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class CommentWriterPointConflictTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  @TempDir Path directory;

  @Test
  void paragraphTextFindingBeforePointDoesNotDuplicateText() throws Exception {
    assertCombinedFindings(true, false);
  }

  @Test
  void pointBeforeParagraphTextFindingPreservesTextAndHighlight() throws Exception {
    assertCombinedFindings(true, true);
  }

  @Test
  void runTextFindingBeforePointPreservesTextAndHighlight() throws Exception {
    assertCombinedFindings(false, false);
  }

  @Test
  void pointBeforeRunTextFindingPreservesTextAndHighlight() throws Exception {
    assertCombinedFindings(false, true);
  }

  @Test
  void pointAnchorRecordsTabsAndManualBreaksBeforePageBreak() throws Exception {
    for (String prefix : List.of("<w:tab/>", "<w:br/>", "<w:cr/>")) {
      Path input = create(prefix);
      Path output = directory.resolve("offset-output.docx");
      var finding = finding("ZH-02-BLANK", List.of(0, 2), 0, 0);
      var report = new CommentWriter().writeCopy(input, output, List.of(finding));
      assertTrue(report.outputWritten(), report.toString());
      assertEquals(AnnotationStatus.WRITTEN, report.items().getFirst().status(), prefix);
      assertEquals(finding.source(), report.items().getFirst().source());
      assertEquals(new SourceAddress("word/document.xml", List.of(0, 1), 1, 1),
          report.items().getFirst().anchorSource(), prefix);
      Document document = read(output);
      assertEquals("PrevAbstract", document.getDocumentElement().getTextContent());
      for (String marker : List.of("commentRangeStart", "commentRangeEnd", "commentReference"))
        assertEquals(1, document.getElementsByTagNameNS(W, marker).getLength(), prefix);
      Element point = (Element) document.getElementsByTagNameNS(W, "commentRangeStart").item(0);
      assertTrue(point.getPreviousSibling() instanceof Element, prefix);
      Element preceding = (Element) point.getPreviousSibling();
      assertEquals("r", preceding.getLocalName(), prefix);
      var breaks = preceding.getElementsByTagNameNS(W, "br");
      assertEquals("page", ((Element) breaks.item(breaks.getLength() - 1)).getAttributeNS(W, "type"), prefix);
    }
  }

  private void assertCombinedFindings(boolean paragraphSource, boolean pointFirst) throws Exception {
    Path input = create();
    Path output = directory.resolve("output.docx");
    var point = finding("ZH-02-BLANK", List.of(0, 2), 0, 0);
    var text = finding("ZH-01-SIZE", paragraphSource ? List.of(0, 1) : List.of(0, 1, 0), 0, 8);
    var result = new CommentWriter().writeCopy(input, output,
        pointFirst ? List.of(point, text) : List.of(text, point));

    assertTrue(result.outputWritten(), result.toString());
    assertEquals(2, result.items().size());
    assertTrue(result.items().stream().allMatch(item -> item.status() == AnnotationStatus.WRITTEN),
        result.toString());
    Document document = read(output);
    assertEquals("PrevAbstract", document.getDocumentElement().getTextContent(),
        "Writing a point comment must not duplicate or remove the title.");
    assertEquals(3, document.getElementsByTagNameNS(W, "p").getLength());
    assertEquals(1, document.getElementsByTagNameNS(W, "br").getLength());

    StringBuilder highlighted = new StringBuilder();
    var highlights = document.getElementsByTagNameNS(W, "highlight");
    for (int index = 0; index < highlights.getLength(); index++)
      highlighted.append(highlights.item(index).getParentNode().getParentNode().getTextContent());
    assertEquals("Abstract", highlighted.toString(),
        "The text finding must still highlight its text after a run is split.");

    Set<String> starts = markerIds(document, "commentRangeStart");
    assertEquals(2, starts.size());
    assertEquals(starts, markerIds(document, "commentRangeEnd"));
    assertEquals(starts, markerIds(document, "commentReference"));
    var texts = new ArrayList<String>();
    for (String id : starts) texts.add(commentRangeText(document, id));
    texts.sort(String::compareTo);
    assertEquals(List.of("", "Abstract"), texts,
        "The point must not turn the existing text comment into a second end-of-paragraph point.");

    Element pointStart = null;
    var startNodes = document.getElementsByTagNameNS(W, "commentRangeStart");
    for (int index = 0; index < startNodes.getLength(); index++) {
      Element start = (Element) startNodes.item(index);
      Node next = start.getNextSibling();
      if (next instanceof Element end && "commentRangeEnd".equals(end.getLocalName())
          && start.getAttributeNS(W, "id").equals(end.getAttributeNS(W, "id")))
        pointStart = start;
    }
    assertNotNull(pointStart);
    assertTrue(pointStart.getPreviousSibling() instanceof Element);
    Element preceding = (Element) pointStart.getPreviousSibling();
    assertEquals("r", preceding.getLocalName());
    assertEquals(1, preceding.getElementsByTagNameNS(W, "br").getLength(),
        "The point must be placed after the live page break, not after copied title text.");
    assertEquals("", preceding.getTextContent());
    var placement = result.items().stream().filter(item -> item.ruleId().equals("ZH-02-BLANK"))
        .findFirst().orElseThrow();
    assertEquals(new SourceAddress("word/document.xml", List.of(0, 1), 0, 0), placement.anchorSource());
  }

  private static Set<String> markerIds(Document document, String name) {
    var elements = document.getElementsByTagNameNS(W, name);
    assertEquals(2, elements.getLength(), name);
    Set<String> ids = new HashSet<>();
    for (int index = 0; index < elements.getLength(); index++)
      assertTrue(ids.add(((Element) elements.item(index)).getAttributeNS(W, "id")), name);
    return ids;
  }

  private static String commentRangeText(Document document, String id) {
    boolean[] active = {false};
    StringBuilder text = new StringBuilder();
    collectRangeText(document.getDocumentElement(), id, active, text);
    return text.toString();
  }

  private static void collectRangeText(Element element, String id, boolean[] active, StringBuilder text) {
    if (W.equals(element.getNamespaceURI())) {
      if ("commentRangeStart".equals(element.getLocalName()) && id.equals(element.getAttributeNS(W, "id"))) active[0] = true;
      if ("commentRangeEnd".equals(element.getLocalName()) && id.equals(element.getAttributeNS(W, "id"))) active[0] = false;
      if ("t".equals(element.getLocalName()) && active[0]) text.append(element.getTextContent());
    }
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
      if (child instanceof Element nested) collectRangeText(nested, id, active, text);
  }

  private static CheckResult finding(String rule, List<Integer> path, int start, int end) {
    return new CheckResult(rule, Applicability.APPLICABLE, ExecutionStatus.EXECUTED, Judgement.FAIL,
        "32", "24", "TEST_POINT_CONFLICT", new SourceAddress("word/document.xml", path, start, end));
  }

  private Path create() throws Exception {
    return create("");
  }

  private Path create(String beforePageBreak) throws Exception {
    Path input = directory.resolve("input.docx");
    try (var zip = new ZipOutputStream(Files.newOutputStream(input))) {
      put(zip, "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
          + "<Override PartName=\"/word/document.xml\" ContentType=\"application/xml\"/></Types>");
      put(zip, "word/document.xml", "<w:document xmlns:w=\"" + W + "\"><w:body>"
          + "<w:p><w:r><w:t>Prev</w:t></w:r></w:p>"
          + "<w:p><w:r><w:rPr><w:sz w:val=\"24\"/></w:rPr>" + beforePageBreak + "<w:br w:type=\"page\"/>"
          + "<w:t>Abstract</w:t></w:r></w:p><w:p/></w:body></w:document>");
    }
    return input;
  }

  private static void put(ZipOutputStream zip, String name, String value) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(value.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static Document read(Path path) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    try (var zip = new ZipFile(path.toFile());
        var stream = zip.getInputStream(zip.getEntry("word/document.xml"))) {
      return factory.newDocumentBuilder().parse(stream);
    }
  }
}
