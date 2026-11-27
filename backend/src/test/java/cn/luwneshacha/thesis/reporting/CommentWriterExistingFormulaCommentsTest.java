package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.Judgement;
import cn.luwneshacha.thesis.rules.RuleEngine;
import cn.luwneshacha.thesis.rules.RuleSet;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.PartName;
import org.docx4j.openpackaging.parts.WordprocessingML.CommentsPart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class CommentWriterExistingFormulaCommentsTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String M = "http://schemas.openxmlformats.org/officeDocument/2006/math";
  private static final String HUMAN = "人工老师";
  @TempDir Path temp;

  @ParameterizedTest
  @CsvSource({"false, 1", "true, 1", "false, 2", "true, 2"})
  void writesAndRefreshesParsedOmmlNumberFindingsBesideExistingComments(
      boolean humanComment, int equationCount) throws Exception {
    Path original = fixture(equationCount);
    var parsedOriginal = new Docx4jDocumentParser().parse(new DocxReader().read(original));
    assertEquals(equationCount, parsedOriginal.equations().size(),
        "Each numbered array row must be parsed as its own formula");
    Path source = temp.resolve("with-existing-comment.docx");
    assertWritten(new CommentWriter().writeCopy(original, source, findings(original)), equationCount);
    if (humanComment) {
      var pkg = WordprocessingMLPackage.load(source.toFile());
      var comments = (CommentsPart) pkg.getParts().getParts().get(new PartName("/word/comments.xml"));
      comments.getContents().getComment().getFirst().setAuthor(HUMAN);
      pkg.save(source.toFile());
    }
    byte[] sourceBytes = Files.readAllBytes(source);
    List<String> existingHuman = humanComments(source);
    assertEquals(humanComment ? 1 : 0, existingHuman.size());
    Document sourceDocument = readXml(source, "word/document.xml");
    Element paragraph = (Element) sourceDocument.getElementsByTagNameNS(W, "p").item(0);
    assertEquals(1, paragraph.getElementsByTagNameNS(W, "commentRangeStart").getLength(),
        "The pre-existing comment must share the formula paragraph and precede its OMML subtree");

    Path output = temp.resolve("annotated.docx");
    var firstFindings = findings(source);
    assertEquals(equationCount, firstFindings.size(), firstFindings.toString());
    var raw = new DocxReader().read(source);
    assertWritten(new CommentWriter().writeCopy(source, output, raw.sourceSha256(), firstFindings),
        equationCount);
    assertCommentIntegrity(output, humanComment ? 2 : 1);
    assertEquals(existingHuman, humanComments(output), "Existing human comments must survive");
    assertTrue(bodyWithoutAnnotations(original).isEqualNode(bodyWithoutAnnotations(output)),
        "Formula arrays, fractions, subscripts, text and all original formatting must remain intact");

    Path repeated = temp.resolve("annotated-again.docx");
    var repeatedFindings = findings(output);
    assertEquals(equationCount, repeatedFindings.size(), repeatedFindings.toString());
    assertWritten(new CommentWriter().writeCopy(output, repeated, repeatedFindings), equationCount);
    assertCommentIntegrity(repeated, humanComment ? 2 : 1);
    assertEquals(existingHuman, humanComments(repeated));
    assertTrue(bodyWithoutAnnotations(original).isEqualNode(bodyWithoutAnnotations(repeated)));
    assertArrayEquals(sourceBytes, Files.readAllBytes(source), "Never modify the inspected input");
  }

  @Test
  void stillRejectsAnOutOfRangeSourceInsteadOfFallingBackToAnotherParagraph() throws Exception {
    Path source = fixture(1);
    var finding = findings(source).getFirst();
    var invalid = new CheckResult(finding.ruleId(), finding.applicability(), finding.executionStatus(),
        finding.judgement(), finding.expected(), finding.actual(), finding.reasonCode(),
        new SourceAddress("word/document.xml", List.of(0, 999), 0, 5));
    Path target = temp.resolve("invalid-source.docx");

    var report = new CommentWriter().writeCopy(source, target, List.of(invalid));

    assertFalse(report.outputWritten());
    assertEquals(1, report.items().size());
    assertEquals(AnnotationStatus.UNSUPPORTED_ANCHOR, report.items().getFirst().status());
    assertEquals("PATH_OUT_OF_RANGE", report.items().getFirst().reason());
  }

  private static List<CheckResult> findings(Path source) throws Exception {
    var parsed = new Docx4jDocumentParser().parse(new DocxReader().read(source));
    return new RuleEngine().check(parsed, RuleSet.schoolV1()).stream()
        .filter(item -> "EQ-02".equals(item.ruleId()) && item.judgement() == Judgement.FAIL).toList();
  }

  private static void assertWritten(AnnotationReport report, int expected) {
    assertTrue(report.outputWritten(), report.items().toString());
    assertEquals(expected, report.items().size(), report.items().toString());
    assertTrue(report.items().stream().allMatch(item -> item.status() == AnnotationStatus.WRITTEN),
        report.items().toString());
  }

  private Path fixture(int equationCount) throws Exception {
    String properties = "<w:rPr><w:rFonts w:ascii=\"Cambria Math\" w:hAnsi=\"Cambria Math\"/>"
        + "<w:sz w:val=\"24\"/></w:rPr>";
    StringBuilder math = new StringBuilder("<m:oMath><m:eqArr>");
    for (int number = 1; number <= equationCount; number++) {
      math.append("<m:e><m:f><m:num><m:r>").append(properties)
          .append("<m:t>1</m:t></m:r></m:num><m:den><m:sSub><m:e><m:r>")
          .append(properties).append("<m:t>x</m:t></m:r></m:e><m:sub><m:r>")
          .append(properties).append("<m:t>0</m:t></m:r></m:sub></m:sSub></m:den></m:f>")
          .append("<m:r>").append(properties).append("<m:t>=1</m:t></m:r><m:r>")
          .append(properties).append("<m:t>#</m:t></m:r><m:r>").append(properties)
          .append("<m:t>(3-").append(number)
          .append(")</m:t></m:r></m:e>");
    }
    math.append("</m:eqArr></m:oMath>");
    String xml = "<w:document xmlns:w=\"" + W + "\" xmlns:m=\"" + M
        + "\"><w:body><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><m:oMathPara>"
        + math + "</m:oMathPara></w:p><w:sectPr/></w:body></w:document>";
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((org.docx4j.wml.Document) XmlUtils.unmarshalString(xml));
    Path source = temp.resolve("original.docx");
    pkg.save(source.toFile());
    return source;
  }

  private static void assertCommentIntegrity(Path path, int expected) throws Exception {
    Document document = readXml(path, "word/document.xml");
    Document comments = readXml(path, "word/comments.xml");
    Set<String> ids = ids(comments, "comment");
    assertEquals(expected, ids.size());
    for (String tag : List.of("commentRangeStart", "commentRangeEnd", "commentReference")) {
      assertEquals(expected, document.getElementsByTagNameNS(W, tag).getLength(), tag);
      assertEquals(ids, ids(document, tag), tag);
    }
    assertEquals(0, document.getElementsByTagNameNS(M, "highlight").getLength());
  }

  private static Set<String> ids(Document document, String localName) {
    Set<String> ids = new HashSet<>();
    var elements = document.getElementsByTagNameNS(W, localName);
    for (int index = 0; index < elements.getLength(); index++)
      assertTrue(ids.add(((Element) elements.item(index)).getAttributeNS(W, "id")),
          "Duplicate " + localName + " ID");
    return ids;
  }

  private static List<String> humanComments(Path path) throws Exception {
    List<String> result = new ArrayList<>();
    var comments = readXml(path, "word/comments.xml").getElementsByTagNameNS(W, "comment");
    for (int index = 0; index < comments.getLength(); index++) {
      Element comment = (Element) comments.item(index);
      if (HUMAN.equals(comment.getAttributeNS(W, "author")))
        result.add(comment.getAttributeNS(W, "id") + "|" + comment.getTextContent());
    }
    return List.copyOf(result);
  }

  private static Element bodyWithoutAnnotations(Path path) throws Exception {
    Document document = readXml(path, "word/document.xml");
    for (String tag : List.of("commentRangeStart", "commentRangeEnd", "highlight")) {
      var nodes = document.getElementsByTagNameNS(W, tag);
      while (nodes.getLength() > 0) nodes.item(0).getParentNode().removeChild(nodes.item(0));
    }
    var references = document.getElementsByTagNameNS(W, "commentReference");
    while (references.getLength() > 0) {
      Node run = references.item(0).getParentNode();
      run.getParentNode().removeChild(run);
    }
    return (Element) document.getElementsByTagNameNS(W, "body").item(0);
  }

  private static Document readXml(Path path, String part) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    try (var zip = new ZipFile(path.toFile()); var input = zip.getInputStream(zip.getEntry(part))) {
      return factory.newDocumentBuilder().parse(input);
    }
  }
}
