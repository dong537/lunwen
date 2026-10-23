package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.RuleEngine;
import cn.luwneshacha.thesis.rules.RuleSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.WordprocessingML.NumberingDefinitionsPart;
import org.docx4j.wml.Numbering;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class EmptyNumberingCommentReadbackTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String NS = "xmlns:w=\"" + W + "\"";
  private static final String NUM_PR = "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>";
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(strings = {"plain", "section", "empty-run", "bookmarks", "table"})
  void writesNumberingPointInsideOriginalEmptyParagraphWithoutChangingContent(String shape) throws Exception {
    String properties = NUM_PR + (shape.equals("section")
        ? "<w:kinsoku w:val=\"0\"/><w:spacing w:line=\"360\" w:lineRule=\"auto\"/>"
            + "<w:rPr><w:sz w:val=\"24\"/></w:rPr><w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>"
            + "<w:pgMar w:top=\"1474\" w:right=\"1474\" w:bottom=\"1474\" w:left=\"1474\""
            + " w:header=\"851\" w:footer=\"992\" w:gutter=\"0\"/></w:sectPr>" : "");
    String content = switch (shape) {
      case "empty-run" -> "<w:r><w:rPr><w:sz w:val=\"24\"/></w:rPr></w:r>";
      case "bookmarks" -> "<w:bookmarkStart w:id=\"7\" w:name=\"empty-list\"/><w:bookmarkEnd w:id=\"7\"/>";
      default -> "";
    };
    String paragraph = "<w:p><w:pPr>" + properties + "</w:pPr>" + content + "</w:p>";
    String body = shape.equals("table")
        ? "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"2000\"/></w:tblGrid><w:tr><w:tc>"
            + paragraph + "</w:tc></w:tr></w:tbl>" : paragraph;
    Path input = fixture("<w:p><w:r><w:t>前段</w:t></w:r></w:p>" + body
        + "<w:p><w:r><w:t>后段</w:t></w:r></w:p>");
    byte[] original = Files.readAllBytes(input);
    var raw = new DocxReader().read(input);
    var parsed = new Docx4jDocumentParser().parse(raw);
    var findings = numberingFindings(parsed);
    assertEquals(1, findings.size());
    SourceAddress source = findings.getFirst().source();
    assertEquals(0, source.start());
    assertEquals(0, source.end());
    Document before = document(input);
    Element originalParagraph = resolve(before, source.elementPath());
    assertEquals("p", originalParagraph.getLocalName());
    assertEquals(0, originalParagraph.getElementsByTagNameNS(W, "t").getLength());

    Path output = temp.resolve("annotated.docx");
    var selection = AnnotationFindings.select(findings);
    var report = new CommentWriter().writeCopy(input, output, raw.sourceSha256(), selection.representatives());

    assertTrue(report.outputWritten(), report.toString());
    assertEquals(1, report.items().size());
    assertEquals(AnnotationStatus.WRITTEN, report.items().getFirst().status(), report.toString());
    assertEquals(source, report.items().getFirst().source());
    assertNull(report.items().getFirst().anchorSource(), "原位批注沿用 source，不伪造迁移位置");
    assertEquals(1, selection.match(report).size());
    Document after = document(output);
    Element annotatedParagraph = resolve(after, source.elementPath());
    assertPointInParagraph(annotatedParagraph, "0");
    assertEquals(1, after.getElementsByTagNameNS(W, "commentRangeStart").getLength());
    assertEquals(0, annotatedParagraph.getElementsByTagNameNS(W, "t").getLength());
    removeCommentMarkup(after);
    assertTrue(before.getDocumentElement().isEqualNode(after.getDocumentElement()),
        "仅允许新增批注标记，原段属性、编号、分节、书签、正文和相邻段落必须完全保留");
    var comments = CommentWriter.readThreads(output);
    assertEquals(1, comments.size());
    assertTrue(comments.getFirst().text().contains("取消该段的编号或多级列表"));
    var reread = new Docx4jDocumentParser().parse(new DocxReader().read(output));
    assertEquals(parsed.automaticNumbering(), reread.automaticNumbering());
    assertEquals(parsed.paragraphs(), reread.paragraphs());
    assertTrue(java.util.Arrays.equals(original, Files.readAllBytes(input)));
  }

  @Test
  void repeatedAnnotationAddsDistinctZeroWidthRangesToTheSameEmptyParagraph() throws Exception {
    Path input = fixture("<w:p><w:pPr>" + NUM_PR + "</w:pPr></w:p>");
    var parsed = new Docx4jDocumentParser().parse(new DocxReader().read(input));
    var findings = numberingFindings(parsed);
    assertEquals(1, findings.size());
    Path first = temp.resolve("first.docx");
    Path second = temp.resolve("second.docx");
    assertTrue(new CommentWriter().writeCopy(input, first, findings).items().stream()
        .allMatch(item -> item.status() == AnnotationStatus.WRITTEN));
    var report = new CommentWriter().writeCopy(first, second, findings);
    assertEquals(AnnotationStatus.WRITTEN, report.items().getFirst().status(), report.toString());
    var paragraph = resolve(document(second), findings.getFirst().source().elementPath());
    assertPointInParagraph(paragraph, "0");
    assertPointInParagraph(paragraph, "1");
    assertEquals(2, CommentWriter.readThreads(second).size());
    assertEquals(0, paragraph.getElementsByTagNameNS(W, "t").getLength());
  }

  @Test
  void doesNotTreatInvalidNonzeroNumberingRangeAsAnEmptyParagraphPoint() throws Exception {
    Path input = fixture("<w:p><w:pPr>" + NUM_PR + "</w:pPr></w:p>");
    var parsed = new Docx4jDocumentParser().parse(new DocxReader().read(input));
    var original = numberingFindings(parsed).getFirst();
    var invalid = new CheckResult(original.ruleId(), original.applicability(), original.executionStatus(),
        original.judgement(), original.expected(), original.actual(), original.reasonCode(),
        new SourceAddress("word/document.xml", original.source().elementPath(), 0, 1));
    var report = new CommentWriter().writeCopy(input, temp.resolve("invalid.docx"), List.of(invalid));
    assertEquals(AnnotationStatus.UNSUPPORTED_ANCHOR, report.items().getFirst().status());
    assertFalse(report.items().getFirst().reason().isBlank());
  }

  private static List<CheckResult> numberingFindings(cn.luwneshacha.thesis.document.ParsedDocument parsed) {
    return new RuleEngine().check(parsed, RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("NUMBERING-01")).toList();
  }

  private static void assertPointInParagraph(Element paragraph, String id) {
    Element start = null;
    Element end = null;
    Element reference = null;
    for (Element child : children(paragraph)) {
      if (child.getLocalName().equals("commentRangeStart") && child.getAttributeNS(W, "id").equals(id)) start = child;
      if (child.getLocalName().equals("commentRangeEnd") && child.getAttributeNS(W, "id").equals(id)) end = child;
      var references = child.getElementsByTagNameNS(W, "commentReference");
      for (int index = 0; index < references.getLength(); index++) {
        Element candidate = (Element) references.item(index);
        if (candidate.getAttributeNS(W, "id").equals(id)) reference = candidate;
      }
    }
    assertNotNull(start);
    assertNotNull(end);
    assertNotNull(reference);
    assertEquals(end, start.getNextSibling(), "批注范围必须原位零宽");
    assertEquals(reference.getParentNode(), end.getNextSibling());
    assertEquals("pPr", children(paragraph).getFirst().getLocalName());
  }

  private static void removeCommentMarkup(Document document) {
    for (String name : List.of("commentRangeStart", "commentRangeEnd", "commentReference")) {
      var nodes = document.getElementsByTagNameNS(W, name);
      while (nodes.getLength() > 0) {
        Node node = nodes.item(0);
        if (name.equals("commentReference")) node = node.getParentNode();
        node.getParentNode().removeChild(node);
      }
    }
  }

  private static Element resolve(Document document, List<Integer> path) {
    Element element = document.getDocumentElement();
    for (int index : path) element = children(element).get(index);
    return element;
  }

  private static List<Element> children(Element element) {
    var children = new ArrayList<Element>();
    for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
      if (child instanceof Element item) children.add(item);
    return children;
  }

  private static Document document(Path path) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    try (var zip = new ZipFile(path.toFile()); var input = zip.getInputStream(zip.getEntry("word/document.xml"))) {
      return factory.newDocumentBuilder().parse(input);
    }
  }

  private Path fixture(String body) throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((org.docx4j.wml.Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>" + body + "<w:sectPr/></w:body></w:document>"));
    var numbering = new NumberingDefinitionsPart();
    numbering.setContents((Numbering) XmlUtils.unmarshalString("<w:numbering " + NS + ">"
        + "<w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>"
        + "<w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"[%1]\"/></w:lvl></w:abstractNum>"
        + "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>"));
    pkg.getMainDocumentPart().addTargetPart(numbering);
    Path source = temp.resolve("source.docx");
    pkg.save(source.toFile());
    return source;
  }
}
