package cn.luwneshacha.thesis.rules;

import static org.junit.jupiter.api.Assertions.*;

import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.reporting.AnnotationStatus;
import cn.luwneshacha.thesis.reporting.CommentWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.openpackaging.parts.PartName;
import org.docx4j.openpackaging.parts.WordprocessingML.HeaderPart;
import org.docx4j.wml.Document;
import org.docx4j.wml.Hdr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CoverHeaderProhibitionTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  private static final String TEXT = "<w:p><w:r><w:t>山东石油化工学院本科毕业设计（论文）</w:t></w:r></w:p>";
  @TempDir Path temp;

  @Test
  void rejectsCoverHeaderAndWritesSpecificCommentWithoutRemovingTheHeader() throws Exception {
    Path source = fixture(false, TEXT, "<w:p/>", false);
    var parsed = parse(source);
    var findings = coverFindings(parsed);
    assertEquals(1, findings.size(), findings.toString());
    assertEquals(Judgement.FAIL, findings.getFirst().judgement());
    Path target = temp.resolve("annotated.docx");
    var report = new CommentWriter().writeCopy(source, target, findings);
    assertEquals(AnnotationStatus.WRITTEN, report.items().getFirst().status());
    String comment = read(target, "word/comments.xml").getDocumentElement().getTextContent();
    assertTrue(comment.contains("封面"), comment);
    assertTrue(comment.contains("删除"), comment);
    assertTrue(comment.contains("后一节的页眉中取消“链接到前一节”"), comment);
    assertTrue(read(source, "word/header1.xml").isEqualNode(read(target, "word/header1.xml")));
  }

  @Test
  void blankFirstHeaderDoesNotInheritVisibleDefaultOnCover() throws Exception {
    assertTrue(coverFindings(parse(fixture(true, TEXT, "<w:p/>", true))).isEmpty());
  }

  @Test
  void firstHeaderIsForbiddenEvenWithAnEmptyDefault() throws Exception {
    var findings = coverFindings(parse(fixture(true, "<w:p/>", TEXT, true)));
    assertEquals(1, findings.size());
    assertEquals(Judgement.FAIL, findings.getFirst().judgement());
  }

  @Test
  void disabledFirstHeaderIsNotACoverViolation() throws Exception {
    assertTrue(coverFindings(parse(fixture(false, "<w:p/>", TEXT, true))).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"<w:p/>", "<w:p><w:r><w:t xml:space='preserve'> 　</w:t></w:r></w:p>",
      "<w:p><w:del><w:r><w:delText>旧页眉</w:delText></w:r></w:del></w:p>"})
  void trulyEmptyCoverHeaderIsAllowed(String content) throws Exception {
    assertTrue(coverFindings(parse(fixture(false, content, "<w:p/>", false))).isEmpty());
  }

  @ParameterizedTest
  @ValueSource(strings = {"<w:p><w:r><w:fldChar w:fldCharType='begin'/><w:instrText> PAGE </w:instrText>"
      + "<w:fldChar w:fldCharType='end'/></w:r></w:p>",
      "<w:p><w:r><w:rPr><w:vanish/></w:rPr><w:t>隐藏页眉</w:t></w:r></w:p>",
      "<w:p><w:pPr><w:pBdr><w:bottom w:val='single' w:sz='6'/></w:pBdr></w:pPr></w:p>",
      "<w:p><w:r><w:rPr><w:highlight w:val='yellow'/></w:rPr><w:t xml:space='preserve'>　　</w:t></w:r></w:p>",
      "<w:p><w:r><w:noBreakHyphen/></w:r></w:p>",
      "<w:p><w:r><w:pict xmlns:v='urn:schemas-microsoft-com:vml'><v:shape id='s1'/></w:pict></w:r></w:p>"})
  void uncertainNonTextOrHiddenHeaderNeverClaimsEmptyOrCertainError(String content) throws Exception {
    var findings = coverFindings(parse(fixture(false, content, "<w:p/>", false)));
    assertEquals(1, findings.size(), findings.toString());
    assertEquals(Judgement.REVIEW, findings.getFirst().judgement());
  }

  @Test
  void sameSectionDefaultIsCheckedForCoverAndLaterAbstractIndependently() throws Exception {
    var document = parse(fixture(false, TEXT, "<w:p/>", true));
    var evaluated = new HeaderRuleModule().evaluate(document, RuleSet.schoolV1());
    assertEquals(1, coverFindings(document).size());
    assertTrue(evaluated.ruleRuns().stream().anyMatch(r -> r.ruleId().equals("HEADER-01-TEXT")
        && r.checkedCount() == 1));
  }

  @Test
  void coverAfterALeadingPageBreakCannotBeCertifiedByAnEmptyFirstHeader() throws Exception {
    Path source = fixture(true, TEXT, "<w:p/>", true, "<w:p><w:r><w:br w:type='page'/></w:r></w:p>");
    var findings = coverFindings(parse(source));
    assertEquals(1, findings.size(), findings.toString());
    assertEquals(Judgement.REVIEW, findings.getFirst().judgement());
  }

  @Test
  void splitCoverCannotBeCertifiedByCheckingOnlyItsFirstText() throws Exception {
    Path source = fixture(true, TEXT, "<w:p/>", true,
        "<w:p><w:r><w:t>山东石油化工学院</w:t><w:br w:type='page'/></w:r></w:p>");
    var findings = coverFindings(parse(source));
    assertEquals(1, findings.size(), findings.toString());
    assertEquals(Judgement.REVIEW, findings.getFirst().judgement());
  }

  @Test
  void breakBetweenTextInLastCoverParagraphIsNotATrailingBreak() throws Exception {
    Path source = fixture(true, TEXT, "<w:p/>", true);
    var pkg = WordprocessingMLPackage.load(source.toFile());
    String xml = XmlUtils.marshaltoString(pkg.getMainDocumentPart().getJaxbElement(), true, true);
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(xml.replace(
        "<w:t>2026年6月</w:t>", "<w:t>学生姓名：张三</w:t><w:br w:type='page'/><w:t>学号：123</w:t>")));
    pkg.save(source.toFile());
    var findings = coverFindings(parse(source));
    assertEquals(1, findings.size(), findings.toString());
    assertEquals(Judgement.REVIEW, findings.getFirst().judgement());
  }

  @Test
  void internalHeaderAndPaginationEvidenceDoNotExpandPublicJson() throws Exception {
    var document = parse(fixture(true, TEXT, "<w:p/>", true));
    String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(document);
    assertFalse(json.contains("pageBoundaryEvidence"), json);
    assertFalse(json.contains("displayContent"), json);
  }

  private List<CheckResult> coverFindings(ParsedDocument document) {
    return new RuleEngine().check(document, RuleSet.schoolV1()).stream()
        .filter(r -> r.ruleId().equals("HEADER-01-COVER") && r.judgement() != Judgement.PASS).toList();
  }

  private ParsedDocument parse(Path source) throws Exception {
    return new Docx4jDocumentParser().parse(new DocxReader().read(source));
  }

  private Path fixture(boolean titlePage, String defaultContent, String firstContent, boolean sameSection)
      throws Exception {
    return fixture(titlePage, defaultContent, firstContent, sameSection, "");
  }

  private Path fixture(boolean titlePage, String defaultContent, String firstContent, boolean sameSection,
      String prefix) throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    String defaultId = addHeader(pkg, defaultContent, "header1.xml");
    String firstId = addHeader(pkg, firstContent, "header2.xml");
    String section = "<w:sectPr><w:headerReference w:type='default' r:id='" + defaultId + "'/>"
        + "<w:headerReference w:type='first' r:id='" + firstId + "'/>"
        + (titlePage ? "<w:titlePg/>" : "") + "</w:sectPr>";
    String cover = "<w:p><w:r><w:t>本科生毕业设计（论文）</w:t></w:r></w:p>"
        + "<w:p>" + (sameSection ? "" : "<w:pPr>" + section + "</w:pPr>")
        + "<w:r><w:t>2026年6月</w:t></w:r></w:p>";
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document xmlns:w='" + W + "' xmlns:r='" + R + "'><w:body>" + prefix + cover
            + "<w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>摘    要</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t>摘要正文</w:t></w:r></w:p>"
            + (sameSection ? section : "<w:sectPr/>") + "</w:body></w:document>"));
    Path source = temp.resolve("input.docx");
    pkg.save(source.toFile());
    return source;
  }

  private String addHeader(WordprocessingMLPackage pkg, String content, String name) throws Exception {
    var header = new HeaderPart(new PartName("/word/" + name));
    header.setJaxbElement((Hdr) XmlUtils.unmarshalString("<w:hdr xmlns:w='" + W + "'>" + content + "</w:hdr>"));
    return pkg.getMainDocumentPart().addTargetPart(header).getId();
  }

  private org.w3c.dom.Document read(Path path, String part) throws Exception {
    try (var zip = new ZipFile(path.toFile())) {
      var factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      return factory.newDocumentBuilder().parse(zip.getInputStream(zip.getEntry(part)));
    }
  }
}
