package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Document;
import org.docx4j.wml.Styles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Docx4jDocumentParserTest {
  private static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";
  @TempDir Path temp;

  @Test
  void resolvesInheritedRunFormattingAndSectionMargins() throws Exception {
    Path source = fixture();

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(source));

    assertEquals(1, document.paragraphs().size());
    assertEquals(2, document.sections().size());
    RunModel run = document.paragraphs().get(0).runs().get(0);
    assertEquals("正文ABC", run.text());
    assertEquals("Times New Roman", run.asciiFont());
    assertEquals("宋体", run.eastAsiaFont());
    assertEquals(24, run.fontSizeHalfPoints());
    assertEquals(480, document.paragraphs().get(0).firstLineIndentTwips());
    assertEquals(1400, document.sections().get(0).topMarginTwips());
    assertEquals(1474, document.sections().get(1).topMarginTwips());
    assertEquals(200, document.paragraphs().get(0).firstLineChars());
    assertTrue(run.source().partName().equals("word/document.xml"));
    assertEquals(java.util.List.of(0, 0), run.source().elementPath());
  }

  @Test
  void collectsRunsNestedInHyperlinksAndContentControls() throws Exception {
    Path source = nestedRunsFixture();

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(source));

    assertEquals(1, document.paragraphs().size());
    assertEquals("超链接控件正文", document.paragraphs().get(0).runs().stream()
        .map(RunModel::text).reduce("", String::concat));
    assertEquals(3, document.paragraphs().get(0).runs().size());
    assertEquals("Times New Roman", document.paragraphs().get(0).runs().get(2).asciiFont());
  }

  @Test
  void indexesTextBoxParagraphsAndReadsTheirEffectiveFormatting() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + " xmlns:v=\"urn:schemas-microsoft-com:vml\"><w:body>"
            + "<w:p><w:r><w:t>第一章 绪论</w:t></w:r></w:p>"
            + "<w:p><w:r><w:pict><v:shape><v:textbox><w:txbxContent><w:p>"
            + "<w:r><w:rPr><w:rFonts w:ascii=\"Arial\" w:eastAsia=\"黑体\"/><w:sz w:val=\"20\"/></w:rPr>"
            + "<w:t>文本框中的文字</w:t></w:r></w:p></w:txbxContent></v:textbox></v:shape></w:pict></w:r></w:p>"
            + "<w:sectPr/></w:body></w:document>"));
    Path file = temp.resolve("textbox.docx");
    pkg.save(file.toFile());

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));

    ParagraphModel textBox = document.paragraphs().stream()
        .filter(paragraph -> paragraph.runs().stream().anyMatch(run -> run.text().contains("文本框中的文字")))
        .findFirst().orElseThrow();
    RunModel run = textBox.runs().get(0);
    assertEquals("Arial", run.asciiFont());
    assertEquals("黑体", run.eastAsiaFont());
    assertEquals(20, run.fontSizeHalfPoints());
    assertEquals("word/document.xml", textBox.source().partName());
    assertTrue(textBox.source().elementPath().size() > 2);

    var sizeFailure = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("BODY-SIZE")
            && result.judgement() == cn.luwneshacha.thesis.rules.Judgement.FAIL
            && result.source().elementPath().equals(textBox.source().elementPath()))
        .findFirst().orElseThrow();
    Path annotated = temp.resolve("textbox-annotated.docx");
    var annotation = new cn.luwneshacha.thesis.reporting.CommentWriter()
        .writeCopy(file, annotated, List.of(sizeFailure));

    assertEquals(cn.luwneshacha.thesis.reporting.AnnotationStatus.WRITTEN, annotation.items().get(0).status());
    try (var zip = new java.util.zip.ZipFile(annotated.toFile())) {
      byte[] documentXml = zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes();
      var root = XmlPartSupport.parse(documentXml).getDocumentElement();
      var textBoxContent = (org.w3c.dom.Element) root.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "txbxContent").item(0);
      assertEquals(1, textBoxContent.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "commentRangeStart").getLength());
    }
  }

  @Test
  void ignoresRunsWithoutVisibleTextSuchAsImages() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:drawing/></w:r></w:p></w:body></w:document>"));
    Path file = temp.resolve("image-run.docx");
    pkg.save(file.toFile());

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));

    assertTrue(document.paragraphs().get(0).runs().isEmpty());
  }

  @Test
  void preservesRunSuperscriptFromWordXml() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:rPr><w:vertAlign w:val=\"superscript\"/></w:rPr>"
            + "<w:t>[12]</w:t></w:r></w:p></w:body></w:document>"));
    Path file = temp.resolve("superscript.docx");
    pkg.save(file.toFile());

    RunModel run = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(0).runs().get(0);

    assertEquals("superscript", run.verticalAlignment());
  }

  @Test
  void recognizesPageFieldsWithSwitchesAndSimpleFieldSyntax() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    String footer = "<w:ftr " + NS + "><w:p><w:r><w:instrText xml:space=\"preserve\"> PA</w:instrText></w:r>"
        + "<w:r><w:instrText xml:space=\"preserve\">GE \\* MERGEFORMAT</w:instrText></w:r></w:p>"
        + "<w:p><w:fldSimple w:instr=\" PAGE \\* MERGEFORMAT \"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p></w:ftr>";
    RawDocx withFooter = addFooter(valid, "word/footer1.xml", "rIdFooter1", footer);

    ParsedDocument document = new Docx4jDocumentParser().parse(withFooter);

    assertEquals(2, document.pageNumbers().size());
    assertTrue(document.pageNumbers().get(0).pageField());
  }

  @Test
  void recognizesPageFieldsThroughStrictFooterRelationship() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    String footer = "<w:ftr " + NS + "><w:p><w:r><w:instrText> PAGE </w:instrText></w:r></w:p></w:ftr>";
    RawDocx transitional = addFooter(valid, "word/footer-strict.xml", "rIdStrictFooter", footer);
    var parts = new LinkedHashMap<>(transitional.parts());
    String documentXml = new String(parts.get("word/document.xml").bytes(), java.nio.charset.StandardCharsets.UTF_8)
        .replace("http://schemas.openxmlformats.org/officeDocument/2006/relationships",
            "http://purl.oclc.org/ooxml/officeDocument/relationships")
        .replace("http://schemas.openxmlformats.org/wordprocessingml/2006/main",
            "http://purl.oclc.org/ooxml/wordprocessingml/main");
    parts.put("word/document.xml", new RawPart("word/document.xml", "test",
        documentXml.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    String footerXml = new String(parts.get("word/footer-strict.xml").bytes(), java.nio.charset.StandardCharsets.UTF_8)
        .replace("http://schemas.openxmlformats.org/wordprocessingml/2006/main",
            "http://purl.oclc.org/ooxml/wordprocessingml/main");
    parts.put("word/footer-strict.xml", new RawPart("word/footer-strict.xml", "test",
        footerXml.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    String relsName = "word/_rels/document.xml.rels";
    String relationships = new String(parts.get(relsName).bytes(), java.nio.charset.StandardCharsets.UTF_8)
        .replace("http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer",
            "http://purl.oclc.org/ooxml/officeDocument/relationships/footer");
    parts.put(relsName, new RawPart(relsName, "test", relationships.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));

    ParsedDocument document = new Docx4jDocumentParser().parse(
        new RawDocx(transitional.sourceSha256(), transitional.sourceBytes(), parts, transitional.entryCount()));

    assertEquals(1, document.pageNumbers().size());
    assertEquals("word/footer-strict.xml", document.pageNumbers().get(0).source().partName());
  }

  @Test
  void ignoresPageFieldsInFooterPartsNotReferencedByAnySection() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    String referenced = "<w:ftr " + NS + "><w:p><w:r><w:instrText> PAGE </w:instrText></w:r></w:p></w:ftr>";
    String orphan = "<w:ftr " + NS + "><w:p><w:pPr><w:jc w:val=\"left\"/></w:pPr>"
        + "<w:r><w:instrText> PAGE </w:instrText></w:r></w:p></w:ftr>";
    RawDocx withFooters = addFooter(valid, "word/footer1.xml", "rIdFooter1", referenced);
    var parts = new LinkedHashMap<>(withFooters.parts());
    parts.put("word/footer2.xml", new RawPart("word/footer2.xml", "test", orphan.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));

    ParsedDocument document = new Docx4jDocumentParser().parse(
        new RawDocx(withFooters.sourceSha256(), withFooters.sourceBytes(), parts, withFooters.entryCount()));

    assertEquals(1, document.pageNumbers().size());
    assertEquals("word/footer1.xml", document.pageNumbers().get(0).source().partName());
  }

  @Test
  void rejectsSectionFooterReferenceWithoutRelationship() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    String footer = "<w:ftr " + NS + "><w:p><w:r><w:instrText> PAGE </w:instrText></w:r></w:p></w:ftr>";
    RawDocx linked = addFooter(valid, "word/footer1.xml", "rIdFooter1", footer);
    var parts = new LinkedHashMap<>(linked.parts());
    String relsName = "word/_rels/document.xml.rels";
    String relationships = new String(parts.get(relsName).bytes(), java.nio.charset.StandardCharsets.UTF_8)
        .replaceAll("<Relationship Id=\\\"rIdFooter1\\\"[^>]*/>", "");
    parts.put(relsName, new RawPart(relsName, "test", relationships.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    RawDocx broken = new RawDocx(linked.sourceSha256(), linked.sourceBytes(), parts, linked.entryCount());

    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
        () -> new Docx4jDocumentParser().parse(broken));
  }

  @Test
  void ignoresFirstPageFooterWhenSectionDoesNotEnableTitlePage() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    String centeredPage = "<w:ftr " + NS + "><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>"
        + "<w:r><w:instrText> PAGE </w:instrText></w:r></w:p></w:ftr>";
    String uncenteredFirstPage = "<w:ftr " + NS + "><w:p><w:pPr><w:jc w:val=\"left\"/></w:pPr>"
        + "<w:r><w:instrText> PAGE </w:instrText></w:r></w:p></w:ftr>";
    RawDocx withDefault = addFooter(valid, "word/footer1.xml", "rIdFooter1", "default", centeredPage);
    RawDocx withFirst = addFooter(withDefault, "word/footer2.xml", "rIdFooter2", "first", uncenteredFirstPage);

    ParsedDocument document = new Docx4jDocumentParser().parse(withFirst);

    assertEquals(1, document.pageNumbers().size());
    assertEquals("word/footer1.xml", document.pageNumbers().get(0).source().partName());
  }

  @Test
  void checksEvenPageFooterOnlyWhenDocumentSettingEnablesIt() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    String centeredPage = "<w:ftr " + NS + "><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>"
        + "<w:r><w:instrText> PAGE </w:instrText></w:r></w:p></w:ftr>";
    String uncenteredEvenPage = "<w:ftr " + NS + "><w:p><w:pPr><w:jc w:val=\"left\"/></w:pPr>"
        + "<w:r><w:instrText> PAGE </w:instrText></w:r></w:p></w:ftr>";
    RawDocx withDefault = addFooter(valid, "word/footer1.xml", "rIdFooter1", "default", centeredPage);
    RawDocx withEven = addFooter(withDefault, "word/footer2.xml", "rIdFooter2", "even", uncenteredEvenPage);
    assertEquals(1, new Docx4jDocumentParser().parse(withEven).pageNumbers().size());

    var parts = new LinkedHashMap<>(withEven.parts());
    String settings = "<w:settings " + NS + "><w:evenAndOddHeaders/></w:settings>";
    parts.put("word/settings.xml", new RawPart("word/settings.xml", "test",
        settings.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    RawDocx withEvenEnabled = new RawDocx(withEven.sourceSha256(), withEven.sourceBytes(), parts, withEven.entryCount());

    ParsedDocument document = new Docx4jDocumentParser().parse(withEvenEnabled);

    assertEquals(2, document.pageNumbers().size());
    assertTrue(document.pageNumbers().stream().anyMatch(page -> page.source().partName().equals("word/footer2.xml")));
  }

  @Test
  void resolvesPageNumberFontFromInheritedFooterParagraphStyle() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    var parts = new LinkedHashMap<>(valid.parts());
    String footer = "<w:ftr " + NS + "><w:p><w:pPr><w:pStyle w:val=\"Footer\"/><w:jc w:val=\"center\"/></w:pPr>"
        + "<w:r><w:instrText> PAGE </w:instrText></w:r></w:p></w:ftr>";
    String styles = "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
        + "<w:name w:val=\"Normal\"/><w:rPr><w:rFonts w:ascii=\"Times New Roman\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Footer\"><w:name w:val=\"Footer\"/>"
        + "<w:basedOn w:val=\"Normal\"/></w:style></w:styles>";
    parts.put("word/footer4.xml", new RawPart("word/footer4.xml", "test", footer.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    parts.put("word/styles.xml", new RawPart("word/styles.xml", "test", styles.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    RawDocx withFooter = addFooter(new RawDocx(valid.sourceSha256(), valid.sourceBytes(), parts, valid.entryCount()),
        "word/footer4.xml", "rIdFooter4", footer);

    ParsedDocument document = new Docx4jDocumentParser().parse(withFooter);

    assertEquals("Times New Roman", document.pageNumbers().get(0).asciiFont());
  }

  @Test
  void resolvesPageNumberFormattingFromItsCharacterStyleBeforeParagraphStyle() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    var parts = new LinkedHashMap<>(valid.parts());
    String footer = "<w:ftr " + NS + "><w:p><w:pPr><w:pStyle w:val=\"Footer\"/><w:jc w:val=\"center\"/></w:pPr>"
        + "<w:r><w:instrText> PAGE </w:instrText></w:r>"
        + "<w:r><w:rPr><w:rStyle w:val=\"PageNumber\"/></w:rPr><w:t>1</w:t></w:r></w:p></w:ftr>";
    String styles = "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
        + "<w:name w:val=\"Normal\"/><w:rPr><w:rFonts w:ascii=\"Times New Roman\"/><w:sz w:val=\"24\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Footer\"><w:basedOn w:val=\"Normal\"/></w:style>"
        + "<w:style w:type=\"character\" w:styleId=\"PageNumber\"><w:rPr>"
        + "<w:rFonts w:ascii=\"Arial\"/><w:sz w:val=\"18\"/></w:rPr></w:style></w:styles>";
    parts.put("word/footer5.xml", new RawPart("word/footer5.xml", "test", footer.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    parts.put("word/styles.xml", new RawPart("word/styles.xml", "test", styles.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    RawDocx withFooter = addFooter(new RawDocx(valid.sourceSha256(), valid.sourceBytes(), parts, valid.entryCount()),
        "word/footer5.xml", "rIdFooter5", footer);

    PageNumberModel pageNumber = new Docx4jDocumentParser().parse(withFooter).pageNumbers().get(0);

    assertEquals("Arial", pageNumber.asciiFont());
    assertEquals(18, pageNumber.sizeHalfPoints());
  }

  @Test
  void doesNotUseParagraphMarkFormattingAsPageNumberRunFormatting() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    var parts = new LinkedHashMap<>(valid.parts());
    String footer = "<w:ftr " + NS + "><w:p><w:pPr><w:pStyle w:val=\"Footer\"/><w:jc w:val=\"center\"/></w:pPr>"
        + "<w:r><w:instrText> PAGE </w:instrText></w:r><w:r><w:t>1</w:t></w:r></w:p></w:ftr>";
    String styles = "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
        + "<w:name w:val=\"Normal\"/><w:rPr><w:rFonts w:ascii=\"Times New Roman\"/><w:sz w:val=\"24\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Footer\"><w:pPr><w:rPr>"
        + "<w:rFonts w:ascii=\"Arial\"/><w:sz w:val=\"18\"/></w:rPr></w:pPr>"
        + "<w:rPr><w:rFonts w:ascii=\"Times New Roman\"/><w:sz w:val=\"24\"/></w:rPr>"
        + "<w:basedOn w:val=\"Normal\"/></w:style></w:styles>";
    parts.put("word/footer6.xml", new RawPart("word/footer6.xml", "test", footer.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    parts.put("word/styles.xml", new RawPart("word/styles.xml", "test", styles.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    RawDocx withFooter = addFooter(new RawDocx(valid.sourceSha256(), valid.sourceBytes(), parts, valid.entryCount()),
        "word/footer6.xml", "rIdFooter6", footer);

    PageNumberModel pageNumber = new Docx4jDocumentParser().parse(withFooter).pageNumbers().get(0);

    assertEquals("Times New Roman", pageNumber.asciiFont());
    assertEquals(24, pageNumber.sizeHalfPoints());
  }

  @Test
  void readsPageNumberFormattingFromItsOwnFooterParagraph() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    var parts = new LinkedHashMap<>(valid.parts());
    String footer = "<w:ftr " + NS + "><w:p><w:pPr><w:jc w:val=\"left\"/></w:pPr>"
        + "<w:r><w:rPr><w:rFonts w:ascii=\"Arial\"/><w:sz w:val=\"40\"/></w:rPr><w:t>页脚说明</w:t></w:r></w:p>"
        + "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>"
        + "<w:r><w:instrText xml:space=\"preserve\"> PAGE </w:instrText></w:r>"
        + "<w:r><w:rPr><w:rFonts w:ascii=\"Times New Roman\"/><w:sz w:val=\"21\"/></w:rPr><w:t>1</w:t></w:r>"
        + "</w:p></w:ftr>";
    RawDocx withFooter = addFooter(valid, "word/footer1.xml", "rIdFooter1", footer);

    PageNumberModel pageNumber = new Docx4jDocumentParser().parse(withFooter).pageNumbers().get(0);

    assertTrue(pageNumber.centered());
    assertEquals("Times New Roman", pageNumber.asciiFont());
    assertEquals(21, pageNumber.sizeHalfPoints());
  }

  @Test
  void refusesToAnnotateWhenRawXmlParagraphIndexCannotBeBound() throws Exception {
    Path source = fixture();
    RawDocx valid = new DocxReader().read(source);
    var parts = new LinkedHashMap<>(valid.parts());
    byte[] noParagraphs = ("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body/></w:document>")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    parts.put("word/document.xml", new RawPart("word/document.xml", "test", noParagraphs, true));

    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
        () -> new Docx4jDocumentParser().parse(new RawDocx(valid.sourceSha256(), valid.sourceBytes(), parts, valid.entryCount())));
  }

  @Test
  void ignoresDeletedRevisionRunsButKeepsInsertedText() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:del><w:r><w:t>删除</w:t></w:r></w:del>"
            + "<w:ins><w:r><w:t>新增</w:t></w:r></w:ins></w:p></w:body></w:document>"));
    Path file = temp.resolve("revisions.docx");
    pkg.save(file.toFile());

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));

    assertEquals(List.of("新增"), document.paragraphs().get(0).runs().stream().map(RunModel::text).toList());
  }

  @Test
  void directTwipsIndentOverridesInheritedCharacterIndent() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:pPr><w:pStyle w:val=\"Normal\"/><w:ind w:firstLine=\"0\"/></w:pPr>"
            + "<w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>"
            + "<w:pPr><w:ind w:firstLineChars=\"200\"/></w:pPr></w:style></w:styles>"));
    Path file = temp.resolve("direct-indent-override.docx");
    pkg.save(file.toFile());

    ParagraphModel paragraph = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(0);

    assertEquals(0, paragraph.firstLineIndentTwips());
    assertEquals(null, paragraph.firstLineChars());
  }

  @Test
  void resolvesHeadingOutlineLevelFromParagraphStyle() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:pPr><w:pStyle w:val=\"Conclusion\"/></w:pPr>"
            + "<w:r><w:t>结论与展望</w:t></w:r></w:p></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:styleId=\"Conclusion\"><w:name w:val=\"Conclusion\"/>"
            + "<w:pPr><w:outlineLvl w:val=\"0\"/></w:pPr></w:style></w:styles>"));
    Path file = temp.resolve("outline-heading.docx");
    pkg.save(file.toFile());

    ParagraphModel paragraph = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(0);

    assertEquals(0, paragraph.outlineLevel());
  }

  @Test
  void resolvesParagraphAlignmentFromStyleAndDirectOverride() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:pPr><w:pStyle w:val=\"Title\"/></w:pPr><w:r><w:t>继承标题</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:pStyle w:val=\"Title\"/><w:jc w:val=\"center\"/></w:pPr>"
            + "<w:r><w:t>直接居中</w:t></w:r></w:p></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/>"
            + "<w:pPr><w:jc w:val=\"right\"/></w:pPr></w:style></w:styles>"));
    Path file = temp.resolve("paragraph-alignment.docx");
    pkg.save(file.toFile());

    var paragraphs = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs();

    assertEquals("right", paragraphs.get(0).alignment());
    assertEquals("center", paragraphs.get(1).alignment());
  }

  @Test
  void chapterBreakEvidenceIncludesInheritedPageBreaksAndPreviousSectionBreaks() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:t>第一章 绪论</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:pStyle w:val=\"Chapter\"/></w:pPr><w:r><w:t>第二章 背景</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:sectPr><w:type w:val=\"nextPage\"/></w:sectPr></w:pPr></w:p>"
            + "<w:p><w:r><w:t>第三章 设计</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t>第二章 背景 ........ 3</w:t></w:r></w:p>"
            + "</w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:styleId=\"Chapter\"><w:name w:val=\"Chapter\"/>"
            + "<w:pPr><w:pageBreakBefore/></w:pPr></w:style></w:styles>"));
    Path file = temp.resolve("chapter-breaks.docx");
    pkg.save(file.toFile());

    List<HeadingBreakModel> breaks = new Docx4jDocumentParser().parse(new DocxReader().read(file)).chapterBreaks();

    assertEquals(List.of("第一章 绪论", "第二章 背景", "第三章 设计"), breaks.stream().map(HeadingBreakModel::text).toList());
    assertEquals(List.of(false, true, true), breaks.stream().map(HeadingBreakModel::explicitBreak).toList());
  }

  private Path nestedRunsFixture() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>"
            + "<w:p><w:hyperlink r:id=\"rId1\"><w:r><w:t>超链接</w:t></w:r></w:hyperlink>"
            + "<w:sdt><w:sdtContent><w:r><w:t>控件</w:t></w:r></w:sdtContent></w:sdt>"
            + "<w:r><w:rPr><w:rFonts w:hAnsi=\"Times New Roman\"/></w:rPr><w:t>正文</w:t></w:r></w:p></w:body></w:document>"));
    Path file = temp.resolve("nested-runs.docx");
    pkg.save(file.toFile());
    return file;
  }
  private Path fixture() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:pPr><w:pStyle w:val=\"Normal\"/><w:ind w:firstLine=\"480\" w:firstLineChars=\"200\"/>"
            + "<w:sectPr><w:pgMar w:top=\"1400\" w:right=\"1474\" w:bottom=\"1474\" w:left=\"1474\"/></w:sectPr></w:pPr>"
            + "<w:r><w:t>正文ABC</w:t></w:r></w:p><w:sectPr><w:pgMar w:top=\"1474\" w:right=\"1474\" w:bottom=\"1474\" w:left=\"1474\"/></w:sectPr></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>"
            + "<w:rPr><w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\" w:eastAsia=\"宋体\"/><w:sz w:val=\"24\"/></w:rPr></w:style></w:styles>"));
    Path file = temp.resolve("format-fixture.docx");
    pkg.save(file.toFile());
    return file;
  }

  private RawDocx addFooter(RawDocx source, String footerPart, String relationshipId, String footerXml) throws Exception {
    return addFooter(source, footerPart, relationshipId, "default", footerXml);
  }

  private RawDocx addFooter(RawDocx source, String footerPart, String relationshipId, String type, String footerXml) throws Exception {
    var parts = new LinkedHashMap<>(source.parts());
    parts.put(footerPart, new RawPart(footerPart, "test", footerXml.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    String document = new String(parts.get("word/document.xml").bytes(), java.nio.charset.StandardCharsets.UTF_8);
    if (!document.contains("xmlns:r="))
      document = document.replace("<w:document ", "<w:document xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" ");
    int sectionEnd = document.lastIndexOf("</w:sectPr>");
    if (sectionEnd < 0) throw new IllegalStateException("fixture has no final section properties");
    document = document.substring(0, sectionEnd)
        + "<w:footerReference w:type=\"" + type + "\" r:id=\"" + relationshipId + "\"/>"
        + document.substring(sectionEnd);
    parts.put("word/document.xml", new RawPart("word/document.xml", "test",
        document.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));

    String relsName = "word/_rels/document.xml.rels";
    String relationships = parts.containsKey(relsName)
        ? new String(parts.get(relsName).bytes(), java.nio.charset.StandardCharsets.UTF_8)
        : "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"></Relationships>";
    String target = footerPart.substring("word/".length());
    relationships = relationships.replace("</Relationships>", "<Relationship Id=\"" + relationshipId
        + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\""
        + target + "\"/></Relationships>");
    parts.put(relsName, new RawPart(relsName, "test", relationships.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    return new RawDocx(source.sourceSha256(), source.sourceBytes(), parts, source.entryCount());
  }
}
