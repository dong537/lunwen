package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.rules.CheckResult;
import cn.luwneshacha.thesis.rules.Judgement;
import cn.luwneshacha.thesis.rules.RuleEngine;
import cn.luwneshacha.thesis.rules.RuleSet;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Document;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class HeadingBlankTypographyAnnotationTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final Set<String> RULE_IDS = Set.of("HEAD-02-BLANK-FONT", "HEAD-02-BLANK-SIZE");
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(strings = {"paragraphMark", "emptyRun", "whitespaceRun"})
  void writesRealBlankLineFailuresAndRefreshesThemWithoutChangingDocumentContent(String formatSource)
      throws Exception {
    Path source = fixture(formatSource);
    var raw = new DocxReader().read(source);
    var parsed = new Docx4jDocumentParser().parse(raw);
    var findings = blankTypographyFindings(source);
    assertEquals(2, findings.size(), findings.toString());
    assertEquals(RULE_IDS, findings.stream().map(CheckResult::ruleId).collect(java.util.stream.Collectors.toSet()));
    assertTrue(findings.stream().allMatch(item -> item.judgement() == Judgement.FAIL), findings.toString());
    assertTrue(findings.stream().allMatch(item -> item.source().elementPath().get(1) == 1), findings.toString());
    assertEquals(3, parsed.paragraphs().size());
    if (formatSource.equals("paragraphMark")) assertTrue(parsed.paragraphs().get(1).runs().isEmpty());

    Path output = temp.resolve(formatSource + "-annotated.docx");
    var report = new CommentWriter().writeCopy(source, output, raw.sourceSha256(), findings);

    assertWritten(report);
    assertEquals(findings.stream().map(CheckResult::source).toList(),
        report.items().stream().map(AnnotationItem::source).toList());
    assertTrue(bodyWithoutCommentAnchors(source).isEqualNode(bodyWithoutCommentAnchors(output)),
        "Text, paragraph/run formatting, page breaks and section properties must remain unchanged");
    assertSingleZeroWidthComment(output);

    // Re-open, detect the same real FAILs, then refresh existing inspection comments.
    var repeatedFindings = blankTypographyFindings(output);
    assertEquals(2, repeatedFindings.size(), repeatedFindings.toString());
    assertTrue(repeatedFindings.stream().allMatch(item -> item.judgement() == Judgement.FAIL));
    Path repeated = temp.resolve(formatSource + "-rechecked.docx");
    var repeatedReport = new CommentWriter().writeCopy(output, repeated,
        new DocxReader().read(output).sourceSha256(), repeatedFindings);

    assertWritten(repeatedReport);
    assertSingleZeroWidthComment(repeated);
    assertTrue(bodyWithoutCommentAnchors(source).isEqualNode(bodyWithoutCommentAnchors(repeated)),
        "Refreshing comments must preserve original document content and formatting");
    assertEquals(raw.sourceSha256(), new DocxReader().read(source).sourceSha256());
  }

  private List<CheckResult> blankTypographyFindings(Path source) throws Exception {
    var document = new Docx4jDocumentParser().parse(new DocxReader().read(source));
    return new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(item -> RULE_IDS.contains(item.ruleId())).toList();
  }

  private Path fixture(String formatSource) throws Exception {
    String runProperties = "<w:rPr><w:rFonts w:ascii='Arial' w:eastAsia='宋体'/>"
        + "<w:sz w:val='24'/></w:rPr>";
    String blank = switch (formatSource) {
      case "paragraphMark" -> "<w:p><w:pPr>" + runProperties + "</w:pPr></w:p>";
      case "emptyRun" -> "<w:p><w:r>" + runProperties + "</w:r></w:p>";
      case "whitespaceRun" -> "<w:p><w:r>" + runProperties + "<w:t xml:space='preserve'> </w:t></w:r></w:p>";
      default -> throw new IllegalArgumentException(formatSource);
    };
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document xmlns:w='" + W + "'><w:body>"
            + "<w:p><w:pPr><w:pageBreakBefore/><w:keepNext/></w:pPr>"
            + "<w:r><w:t>第一章  绪论</w:t></w:r></w:p>" + blank
            + "<w:p><w:pPr><w:spacing w:line='360'/><w:ind w:firstLine='480'/></w:pPr>"
            + "<w:r><w:t>正文内容</w:t></w:r></w:p><w:sectPr>"
            + "<w:pgSz w:w='11906' w:h='16838'/><w:pgMar w:top='1440' w:bottom='1440'/>"
            + "</w:sectPr></w:body></w:document>"));
    Path source = temp.resolve(formatSource + ".docx");
    pkg.save(source.toFile());
    return source;
  }

  private void assertWritten(AnnotationReport report) {
    assertTrue(report.outputWritten(), report.items().toString());
    assertEquals(2, report.items().size(), report.items().toString());
    assertTrue(report.items().stream().allMatch(item -> item.status() == AnnotationStatus.WRITTEN),
        report.items().toString());
  }

  private void assertSingleZeroWidthComment(Path source) throws Exception {
    var document = read(source, "word/document.xml");
    assertEquals(1, document.getElementsByTagNameNS(W, "commentRangeStart").getLength());
    assertEquals(1, document.getElementsByTagNameNS(W, "commentRangeEnd").getLength());
    assertEquals(1, document.getElementsByTagNameNS(W, "commentReference").getLength());
    Node start = document.getElementsByTagNameNS(W, "commentRangeStart").item(0);
    assertEquals("commentRangeEnd", start.getNextSibling().getLocalName());
    assertEquals(0, document.getElementsByTagNameNS(W, "highlight").getLength());
    var comments = read(source, "word/comments.xml");
    assertEquals(1, comments.getElementsByTagNameNS(W, "comment").getLength());
  }

  private Element bodyWithoutCommentAnchors(Path source) throws Exception {
    var document = read(source, "word/document.xml");
    for (String name : List.of("commentRangeStart", "commentRangeEnd", "commentReference")) {
      var matches = document.getElementsByTagNameNS(W, name);
      var remove = new ArrayList<Node>();
      for (int i = 0; i < matches.getLength(); i++) {
        Node node = matches.item(i);
        remove.add(name.equals("commentReference") ? node.getParentNode() : node);
      }
      for (Node node : remove) node.getParentNode().removeChild(node);
    }
    return (Element) document.getElementsByTagNameNS(W, "body").item(0);
  }

  private org.w3c.dom.Document read(Path source, String part) throws Exception {
    try (var zip = new ZipFile(source.toFile())) {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      return factory.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry(part)));
    }
  }
}
