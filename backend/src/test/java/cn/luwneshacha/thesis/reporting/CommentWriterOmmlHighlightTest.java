package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.Applicability;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.ExecutionStatus;
import cn.luwneshacha.thesis.rules.Judgement;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class CommentWriterOmmlHighlightTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String M = "http://schemas.openxmlformats.org/officeDocument/2006/math";
  @TempDir Path temp;

  @ParameterizedTest
  @CsvSource({"true, true", "true, false", "false, true", "false, false"})
  void keepsEquationNumberHighlightInWordRunProperties(boolean mathProperties, boolean wordProperties)
      throws Exception {
    Path source = equationArrayFixture(mathProperties, wordProperties);
    Document original = readXml(source, "word/document.xml");
    Element originalEquation = (Element) original.getElementsByTagNameNS(M, "oMath").item(0);
    Element numberRun = (Element) original.getElementsByTagNameNS(M, "r").item(2);
    var finding = new CheckResult("EQ-02", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "Times New Roman / 21", "Cambria Math / 24",
        "EQUATION_NUMBER_FORMAT_MISMATCH",
        new SourceAddress("word/document.xml", pathOf(numberRun), 10, 16));
    Path target = temp.resolve("equation-highlight.docx");

    var report = new CommentWriter().writeCopy(source, target, List.of(finding));

    assertTrue(report.outputWritten());
    assertEquals(1, report.items().size());
    assertEquals(AnnotationStatus.WRITTEN, report.items().get(0).status());
    assertEquationAndComments(target, originalEquation, mathProperties, wordProperties);
    Path roundTrip = temp.resolve("equation-highlight-roundtrip.docx");
    WordprocessingMLPackage.load(target.toFile()).save(roundTrip.toFile());
    assertEquationAndComments(roundTrip, originalEquation, mathProperties, wordProperties);
  }

  private void assertEquationAndComments(Path documentPath, Element originalEquation,
      boolean mathProperties, boolean wordProperties) throws Exception {
    Document document = readXml(documentPath, "word/document.xml");
    assertEquals(0, document.getElementsByTagNameNS(M, "highlight").getLength(),
        "Word highlight must never be written in the OMML namespace");
    var highlights = document.getElementsByTagNameNS(W, "highlight");
    assertEquals(1, highlights.getLength());
    Element highlight = (Element) highlights.item(0);
    assertEquals("yellow", highlight.getAttributeNS(W, "val"));
    Element properties = (Element) highlight.getParentNode();
    assertEquals(W, properties.getNamespaceURI());
    assertEquals("rPr", properties.getLocalName());
    Element numberRun = (Element) properties.getParentNode();
    assertEquals(M, numberRun.getNamespaceURI());
    assertEquals("r", numberRun.getLocalName());
    assertEquals("(3-10)", numberRun.getElementsByTagNameNS(M, "t").item(0).getTextContent());
    assertEquals(1, numberRun.getElementsByTagNameNS(W, "rPr").getLength());
    List<String> childNames = new ArrayList<>();
    for (Node node = numberRun.getFirstChild(); node != null; node = node.getNextSibling())
      if (node instanceof Element child) childNames.add(child.getNodeName());
    assertEquals(mathProperties ? List.of("m:rPr", "w:rPr", "m:t")
        : List.of("w:rPr", "m:t"), childNames);

    properties.removeChild(highlight);
    if (!wordProperties) numberRun.removeChild(properties);
    Element equation = (Element) document.getElementsByTagNameNS(M, "oMath").item(0);
    assertTrue(originalEquation.isEqualNode(equation),
        "Equation array, text and original run properties must remain unchanged");
    assertEquals(2, document.getElementsByTagNameNS(W, "commentRangeStart").getLength());
    assertEquals(2, document.getElementsByTagNameNS(W, "commentRangeEnd").getLength());
    assertEquals(2, document.getElementsByTagNameNS(W, "commentReference").getLength());
    Document comments = readXml(documentPath, "word/comments.xml");
    assertEquals(2, comments.getElementsByTagNameNS(W, "comment").getLength());
    assertTrue(comments.getDocumentElement().getTextContent().contains("行距"));
    assertTrue(comments.getDocumentElement().getTextContent().contains("公式"));
  }

  private Path equationArrayFixture(boolean mathProperties, boolean wordProperties) throws Exception {
    String runProperties = (mathProperties ? "<m:rPr><m:nor/><m:sty m:val=\"p\"/></m:rPr>" : "")
        + (wordProperties ? "<w:rPr><w:rFonts w:ascii=\"Cambria Math\" w:hAnsi=\"Cambria Math\"/>"
            + "<w:b w:val=\"0\"/><w:i w:val=\"0\"/></w:rPr>" : "");
    String xml = "<w:document xmlns:w=\"" + W + "\" xmlns:m=\"" + M + "\"><w:body>"
        + "<w:p><w:r><w:t>已有批注的正文</w:t></w:r></w:p>"
        + "<w:p><m:oMath><m:eqArr><m:e><m:r><m:t>x+y=1</m:t></m:r></m:e>"
        + "<m:e><m:r><m:t>x-y=0</m:t></m:r><m:r>" + runProperties
        + "<m:t>(3-10)</m:t></m:r></m:e></m:eqArr></m:oMath></w:p>"
        + "<w:sectPr/></w:body></w:document>";
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((org.docx4j.wml.Document) XmlUtils.unmarshalString(xml));
    Path original = temp.resolve("original.docx");
    pkg.save(original.toFile());
    Path source = temp.resolve("source-with-comment.docx");
    var existing = new CheckResult("BODY-LINE", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "360 / auto", "240 / auto", "LINE_SPACING_MISMATCH",
        new SourceAddress("word/document.xml", List.of(0, 0), 0, 0));
    assertTrue(new CommentWriter().writeCopy(original, source, List.of(existing)).outputWritten());
    return source;
  }

  private static Document readXml(Path path, String part) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    try (var zip = new ZipFile(path.toFile()); var input = zip.getInputStream(zip.getEntry(part))) {
      return factory.newDocumentBuilder().parse(input);
    }
  }

  private static List<Integer> pathOf(Element element) {
    var indexes = new ArrayList<Integer>();
    for (Node current = element; current.getParentNode() instanceof Element; current = current.getParentNode()) {
      int index = 0;
      for (Node previous = current.getPreviousSibling(); previous != null; previous = previous.getPreviousSibling())
        if (previous instanceof Element) index++;
      indexes.add(index);
    }
    Collections.reverse(indexes);
    return List.copyOf(indexes);
  }
}
