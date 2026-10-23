package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.*;

import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.*;

class CommentWriterPageStartTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  @TempDir Path directory;

  @Test
  void emptyHeaderMovesToItsOwnSectionPageStartWithoutHighlightingBodyText() throws Exception {
    Path input = create("<w:p><w:pPr><w:sectPr/></w:pPr><w:r><w:t>上一节</w:t></w:r></w:p>"
        + "<w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>摘要</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>摘要内容</w:t></w:r></w:p>"
        + "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"h1\"/></w:sectPr>");
    Path output = directory.resolve("output.docx");
    var result = new CommentWriter().writeCopy(input, output, List.of(finding("HEADER-01-TEXT", "word/header1.xml", List.of(0))));

    assertWritten(result);
    Document document = read(output, "word/document.xml");
    assertEquals("摘要", anchorParagraph(document).getTextContent());
    assertEquals(0, document.getElementsByTagNameNS(W, "highlight").getLength());
    assertEquals(0, read(output, "word/header1.xml").getElementsByTagNameNS(W, "commentRangeStart").getLength());
    assertPreserved(input, output);
  }

  @Test
  void emptyBlankLineFindingMovesToStartOfItsPage() throws Exception {
    Path input = create("<w:p><w:r><w:t>前页</w:t></w:r></w:p>"
        + "<w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>摘    要</w:t></w:r></w:p>"
        + "<w:p/><w:p><w:r><w:t>摘要正文</w:t></w:r></w:p>");
    Path output = directory.resolve("output.docx");
    var result = new CommentWriter().writeCopy(input, output, List.of(finding("ZH-02-BLANK", "word/document.xml", List.of(0, 2))));

    assertWritten(result);
    assertEquals("摘    要", anchorParagraph(read(output, "word/document.xml")).getTextContent());
    assertPreserved(input, output);
  }

  @Test
  void emptySectionPageNumberDoesNotBorrowPreviousSectionText() throws Exception {
    Path input = create("<w:p><w:pPr><w:sectPr/></w:pPr><w:r><w:t>前节内容</w:t></w:r></w:p>"
        + "<w:p><w:pPr><w:sectPr/></w:pPr></w:p>");
    Path output = directory.resolve("output.docx");
    var result = new CommentWriter().writeCopy(input, output, List.of(finding("PAGE-04", "word/document.xml", List.of(0, 1))));

    assertWritten(result);
    assertEquals("", anchorParagraph(read(output, "word/document.xml")).getTextContent());
    assertPreserved(input, output);
  }

  @Test
  void inheritsAbstractPageBreakAndRecordsOriginalAndWrittenPositions() throws Exception {
    Path input = create("<w:p><w:r><w:t>本科生毕业论文</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>2026年6月</w:t></w:r></w:p>"
        + "<w:p><w:pPr><w:pStyle w:val=\"2\"/></w:pPr><w:r><w:t>摘要</w:t></w:r></w:p>"
        + "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"h1\"/></w:sectPr>",
        "<w:styles xmlns:w=\"" + W + "\"><w:style w:styleId=\"1\"><w:pPr><w:pageBreakBefore/></w:pPr></w:style>"
        + "<w:style w:styleId=\"2\"><w:basedOn w:val=\"1\"/></w:style></w:styles>");
    Path output = directory.resolve("output.docx");
    var finding = finding("HEADER-01-TEXT", "word/header1.xml", List.of(0));
    var result = new CommentWriter().writeCopy(input, output, List.of(finding));

    assertWritten(result);
    assertEquals("摘要", anchorParagraph(read(output, "word/document.xml")).getTextContent());
    assertEquals(finding.source(), result.items().getFirst().source());
    assertEquals(new SourceAddress("word/document.xml", List.of(0, 2), 0, 0), result.items().getFirst().anchorSource());
    assertPreserved(input, output);
  }

  @Test
  void pointAnchorIsAfterPageBreakWhenBreakAndTitleShareOneRun() throws Exception {
    for (String marker : List.of("<w:br w:type=\"page\"/>", "<w:lastRenderedPageBreak/>")) {
      Path input = create("<w:p><w:r><w:t>上一页</w:t></w:r></w:p>"
          + "<w:p><w:r>" + marker + "<w:t>摘    要</w:t></w:r></w:p><w:p/>");
      Path output = directory.resolve("output.docx");
      var report = new CommentWriter().writeCopy(input, output,
          List.of(finding("ZH-02-BLANK", "word/document.xml", List.of(0, 2))));
      assertWritten(report);
      var document = read(output, "word/document.xml");
      Node start = document.getElementsByTagNameNS(W, "commentRangeStart").item(0);
      assertNotNull(start.getPreviousSibling(), marker);
      Element preceding = (Element) start.getPreviousSibling();
      String tag = marker.contains("lastRendered") ? "lastRenderedPageBreak" : "br";
      assertEquals(1, preceding.getElementsByTagNameNS(W, tag).getLength(), marker);
      assertEquals("", preceding.getTextContent());
      assertEquals("摘    要", anchorParagraph(document).getTextContent());
      assertPreserved(input, output);
    }
  }

  @Test
  void unknownAutomaticPageExtentKeepsOriginalBlankPoint() throws Exception {
    Path input = create("<w:p><w:r><w:t>篇首</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>一段可能因为自动排版跨越多页的长正文。</w:t></w:r></w:p><w:p/>");
    Path output = directory.resolve("output.docx");
    var result = new CommentWriter().writeCopy(input, output, List.of(finding("ZH-02-BLANK", "word/document.xml", List.of(0, 2))));

    assertWritten(result);
    assertEquals("", anchorParagraph(read(output, "word/document.xml")).getTextContent());
    assertEquals("PLACED_AT_SOURCE_POINT", result.items().getFirst().reason());
    assertPreserved(input, output);
  }

  @Test
  void nonDefaultHeadingPageBreakDoesNotApplyToUnstyledParagraphs() throws Exception {
    Path input = create("<w:p><w:r><w:t>篇首</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>普通正文</w:t></w:r></w:p><w:p/>",
        "<w:styles xmlns:w=\"" + W + "\">"
        + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"/>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:pPr><w:pageBreakBefore/></w:pPr></w:style>"
        + "</w:styles>");
    Path output = directory.resolve("output.docx");
    var result = new CommentWriter().writeCopy(input, output,
        List.of(finding("ZH-02-BLANK", "word/document.xml", List.of(0, 2))));
    assertWritten(result);
    assertEquals("PLACED_AT_SOURCE_POINT", result.items().getFirst().reason());
    assertEquals(List.of(0, 2), result.items().getFirst().anchorSource().elementPath());
    assertPreserved(input, output);
  }

  @Test
  void unsupportedWrappedPageBoundaryReallyKeepsOriginalPosition() throws Exception {
    Path input = create("<w:p><w:r><w:t>前页</w:t></w:r></w:p>"
        + "<w:p><w:hyperlink><w:r><w:br w:type=\"page\"/><w:t>摘要</w:t></w:r></w:hyperlink></w:p><w:p/>");
    Path output = directory.resolve("output.docx");
    var result = new CommentWriter().writeCopy(input, output,
        List.of(finding("ZH-02-BLANK", "word/document.xml", List.of(0, 2))));
    assertWritten(result);
    assertEquals("PLACED_AT_SOURCE_POINT", result.items().getFirst().reason());
    assertEquals(List.of(0, 2), result.items().getFirst().anchorSource().elementPath());
    assertEquals("", anchorParagraph(read(output, "word/document.xml")).getTextContent());
    assertPreserved(input, output);
  }

  @Test
  void sharedHeaderUsesApplicableContextInsteadOfCover() throws Exception {
    Path input = create("<w:p><w:pPr><w:sectPr><w:headerReference w:type=\"default\" r:id=\"h1\"/></w:sectPr></w:pPr>"
        + "<w:r><w:t>封面</w:t></w:r></w:p>"
        + "<w:p><w:r><w:t>摘    要</w:t></w:r></w:p><w:sectPr/>");
    var finding = new CheckResult("HEADER-01-TEXT", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.FAIL, "规定的页眉", "缺失", "TEST_HEADER_CONTEXT",
        new SourceAddress("word/header1.xml", List.of(0), 0, 0),
        new SourceAddress("word/document.xml", List.of(0, 1), 0, 0));
    Path output = directory.resolve("output.docx");
    var result = new CommentWriter().writeCopy(input, output, List.of(finding));
    assertWritten(result);
    assertEquals("摘    要", anchorParagraph(read(output, "word/document.xml")).getTextContent());
    assertEquals(finding.source(), result.items().getFirst().source());
    assertEquals(finding.contextSource(), result.items().getFirst().anchorSource());
    assertPreserved(input, output);
  }

  @Test
  void emptyContinuousSectionKeepsItsOwnPoint() throws Exception {
    Path input = create("<w:p><w:pPr><w:sectPr/></w:pPr><w:r><w:t>目录</w:t></w:r></w:p>"
        + "<w:p/><w:p><w:pPr><w:sectPr><w:type w:val=\"continuous\"/></w:sectPr></w:pPr></w:p>"
        + "<w:p><w:r><w:t>第一章</w:t></w:r></w:p>");
    Path output = directory.resolve("output.docx");
    var finding = finding("PAGE-04", "word/document.xml", List.of(0, 2));
    var result = new CommentWriter().writeCopy(input, output, List.of(finding));

    assertWritten(result);
    assertEquals(finding.source(), result.items().getFirst().anchorSource());
    assertEquals("PLACED_AT_SOURCE_POINT", result.items().getFirst().reason());
    assertPreserved(input, output);
  }

  @Test
  void textlessTerminalHeaderKeepsPointAnchorInsteadOfMovingToPreviousPage() throws Exception {
    Path input = create("<w:p><w:pPr><w:sectPr/></w:pPr><w:r><w:t>致谢正文</w:t></w:r></w:p>"
        + "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"h1\"/></w:sectPr>");
    Path output = directory.resolve("output.docx");
    var result = new CommentWriter().writeCopy(input, output, List.of(finding("HEADER-01-TEXT", "word/header1.xml", List.of(0))));

    assertWritten(result);
    assertEquals(0, read(output, "word/document.xml").getElementsByTagNameNS(W, "commentRangeStart").getLength());
    assertEquals("", anchorParagraph(read(output, "word/header1.xml")).getTextContent());
    assertPreserved(input, output);
  }

  private void assertWritten(AnnotationReport report) {
    assertTrue(report.outputWritten(), report.toString());
    assertEquals(AnnotationStatus.WRITTEN, report.items().getFirst().status(), report.toString());
  }

  private Element anchorParagraph(Document document) {
    var starts = document.getElementsByTagNameNS(W, "commentRangeStart");
    assertEquals(1, starts.getLength());
    Node current = starts.item(0);
    while (current != null && !"p".equals(current.getLocalName())) current = current.getParentNode();
    assertNotNull(current);
    assertEquals(1, document.getElementsByTagNameNS(W, "commentRangeEnd").getLength());
    assertEquals(1, document.getElementsByTagNameNS(W, "commentReference").getLength());
    return (Element) current;
  }

  private void assertPreserved(Path input, Path output) throws Exception {
    for (String name : List.of("word/document.xml", "word/header1.xml")) {
      Document before = read(input, name), after = read(output, name);
      assertEquals(before.getDocumentElement().getTextContent(), after.getDocumentElement().getTextContent());
      for (String element : List.of("p", "sectPr", "pageBreakBefore", "rPr", "ind", "spacing")) {
        assertEquals(before.getElementsByTagNameNS(W, element).getLength(), after.getElementsByTagNameNS(W, element).getLength(), element);
      }
    }
  }

  private CheckResult finding(String rule, String part, List<Integer> path) {
    return new CheckResult(rule, Applicability.APPLICABLE, ExecutionStatus.EXECUTED, Judgement.FAIL,
        "规范要求", "缺失", "TEST_EMPTY_ANCHOR", new SourceAddress(part, path, 0, 0));
  }

  private Path create(String body) throws Exception {
    return create(body, null);
  }

  private Path create(String body, String styles) throws Exception {
    Path path = directory.resolve("input.docx");
    try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
      put(zip, "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Override PartName=\"/word/document.xml\" ContentType=\"application/xml\"/></Types>");
      put(zip, "word/document.xml", "<w:document xmlns:w=\"" + W + "\" xmlns:r=\"" + R + "\"><w:body>" + body + "</w:body></w:document>");
      put(zip, "word/header1.xml", "<w:hdr xmlns:w=\"" + W + "\"><w:p/></w:hdr>");
      if (styles != null) put(zip, "word/styles.xml", styles);
      put(zip, "word/_rels/document.xml.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"h1\" Type=\"" + R + "/header\" Target=\"header1.xml\"/></Relationships>");
    }
    return path;
  }

  private static void put(ZipOutputStream zip, String name, String xml) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(xml.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static Document read(Path path, String part) throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    try (var zip = new ZipFile(path.toFile()); var stream = zip.getInputStream(zip.getEntry(part))) {
      return factory.newDocumentBuilder().parse(stream);
    }
  }
}
