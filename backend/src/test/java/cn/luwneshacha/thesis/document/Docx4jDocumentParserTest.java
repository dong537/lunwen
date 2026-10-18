package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
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
    assertFalse(document.sections().get(0).terminal());
    assertTrue(document.sections().get(1).terminal());
    RunModel run = document.paragraphs().get(0).runs().get(0);
    assertEquals("正文ABC", run.text());
    assertEquals("Times New Roman", run.asciiFont());
    assertEquals("宋体", run.eastAsiaFont());
    assertEquals(24, run.fontSizeHalfPoints());
    assertNull(document.paragraphs().get(0).firstLineIndentTwips());
    assertEquals(1400, document.sections().get(0).topMarginTwips());
    assertEquals(1474, document.sections().get(1).topMarginTwips());
    assertEquals(200, document.paragraphs().get(0).firstLineChars());
    assertTrue(run.source().partName().equals("word/document.xml"));
    assertEquals(java.util.List.of(0, 0), run.source().elementPath());
  }

  @Test
  void treatsOnOffBoldElementWithoutValueAsEnabled() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p>"
            + "<w:r><w:rPr><w:b/></w:rPr><w:t>ABS</w:t></w:r>"
            + "<w:r><w:rPr><w:b w:val=\"1\"/></w:rPr><w:t>TRACT</w:t></w:r>"
            + "<w:r><w:rPr><w:b w:val=\"0\"/></w:rPr><w:t>plain</w:t></w:r>"
            + "</w:p><w:sectPr/></w:body></w:document>"));
    Path source = temp.resolve("bold-onoff.docx");
    pkg.save(source.toFile());

    List<RunModel> runs = new Docx4jDocumentParser().parse(new DocxReader().read(source))
        .paragraphs().get(0).runs();

    assertEquals(List.of(true, true, false), runs.stream().map(RunModel::bold).toList());
  }

  @Test
  void doesNotTreatParagraphMarkOrComplexScriptBoldAsRunBold() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:pPr><w:rPr><w:b/></w:rPr></w:pPr>"
            + "<w:r><w:rPr><w:bCs/></w:rPr><w:t>2022</w:t></w:r></w:p>"
            + "<w:p><w:r><w:rPr><w:bCs/></w:rPr><w:t>2026年 6月8 日</w:t></w:r></w:p>"
            + "<w:sectPr/></w:body></w:document>"));
    Path source = temp.resolve("paragraph-bold-with-complex-script-override.docx");
    pkg.save(source.toFile());

    List<RunModel> runs = new Docx4jDocumentParser().parse(new DocxReader().read(source))
        .paragraphs().stream().flatMap(paragraph -> paragraph.runs().stream()).toList();

    assertEquals(Boolean.FALSE, runs.get(0).bold(), "paragraph-mark w:b does not apply to the existing run");
    assertEquals(Boolean.FALSE, runs.get(1).bold(), "w:bCs alone does not bold Chinese or Latin text");
  }

  @Test
  void usesRegularBoldForChineseCoverFieldsAndDoesNotTreatBcsAsLatinBold() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:t>山东石油化工学院</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t>本科生毕业设计（论文）</w:t></w:r></w:p>"
            + "<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>学生姓名：张三</w:t></w:r></w:p>"
            + "<w:p><w:r><w:rPr><w:bCs/></w:rPr><w:t>学号：2022</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t>摘要</w:t></w:r></w:p>"
            + "<w:p><w:r><w:rPr><w:bCs/></w:rPr><w:t>ABSTRACT</w:t></w:r></w:p>"
            + "<w:p><w:r><w:rPr><w:bCs/></w:rPr><w:t>Keywords</w:t></w:r>"
            + "<w:r><w:t>: alpha; beta; gamma</w:t></w:r></w:p>"
            + "<w:sectPr/></w:body></w:document>"));
    Path source = temp.resolve("complex-script-bold.docx");
    pkg.save(source.toFile());

    var findings = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(new Docx4jDocumentParser().parse(new DocxReader().read(source)),
            cn.luwneshacha.thesis.rules.RuleSet.schoolV1());

    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("COVER-03-BOLD")
        && result.judgement() == cn.luwneshacha.thesis.rules.Judgement.PASS), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("COVER-03-BOLD")
        && result.judgement() == cn.luwneshacha.thesis.rules.Judgement.FAIL), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("EN-01-BOLD")
        && result.judgement() == cn.luwneshacha.thesis.rules.Judgement.FAIL), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.ruleId().equals("EN-03-BOLD")
        && "KEYWORD_LABEL_NOT_BOLD".equals(result.reasonCode())), findings.toString());
  }

  @Test
  void reportsMissingMainBodyPageNumberForValidDocxWithoutFooterReferences() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:t>第一章 绪论</w:t></w:r></w:p>"
            + "<w:sectPr><w:pgMar w:top=\"1474\" w:right=\"1474\" w:bottom=\"1474\" w:left=\"1474\"/>"
            + "</w:sectPr></w:body></w:document>"));
    Path bodyWithoutFooter = temp.resolve("body-without-footer.docx");
    pkg.save(bodyWithoutFooter.toFile());

    ParsedDocument parsed = new Docx4jDocumentParser().parse(new DocxReader().read(bodyWithoutFooter));

    assertTrue(parsed.pageNumbers().isEmpty());
    var missingPageNumber = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(parsed, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(result -> result.reasonCode().equals("PAGE_NUMBER_MISSING_IN_MAIN_BODY"))
        .toList();
    assertTrue(missingPageNumber.stream().anyMatch(result ->
        result.judgement() == cn.luwneshacha.thesis.rules.Judgement.FAIL), missingPageNumber.toString());
  }

  @Test
  void reportsMissingHeaderForValidDocxWithImplicitSectionAndNoHeaderReference() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:t>第一章 绪论</w:t></w:r></w:p></w:body></w:document>"));
    Path source = temp.resolve("implicit-section-without-header.docx");
    pkg.save(source.toFile());

    RawDocx raw = new DocxReader().read(source);
    var xmlRoot = XmlPartSupport.parse(raw.part("word/document.xml").bytes()).getDocumentElement();
    assertEquals(0, xmlRoot.getElementsByTagNameNS(XmlPartSupport.W, "sectPr").getLength());
    assertEquals(0, xmlRoot.getElementsByTagNameNS(XmlPartSupport.W, "headerReference").getLength());

    ParsedDocument parsed = new Docx4jDocumentParser().parse(raw);

    assertEquals(1, parsed.paragraphs().size());
    assertTrue(parsed.sections().isEmpty());
    assertTrue(parsed.headers().isEmpty());
    var missingHeader = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(parsed, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(result -> result.reasonCode().equals("HEADER_MISSING"))
        .toList();
    assertEquals(1, missingHeader.size(), missingHeader.toString());
    assertEquals(cn.luwneshacha.thesis.rules.Judgement.FAIL, missingHeader.get(0).judgement());
  }

  @Test
  void collectsRunsNestedInHyperlinksAndContentControls() throws Exception {
    Path source = nestedRunsFixture();

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(source));

    assertEquals(1, document.paragraphs().size());
    assertEquals("超链接控件正文", document.paragraphs().get(0).runs().stream()
        .map(RunModel::text).reduce("", String::concat));
    assertEquals(3, document.paragraphs().get(0).runs().size());
    assertEquals("Times New Roman", document.paragraphs().get(0).runs().get(2).highAnsiFont());
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
    assertTrue(textBox.insideTextBox());

    var bodyFindings = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1());
    assertTrue(bodyFindings.stream().noneMatch(result -> result.ruleId().startsWith("BODY-")
        && result.source().elementPath().equals(textBox.source().elementPath())), bodyFindings.toString());

    var sizeFailure = new cn.luwneshacha.thesis.rules.CheckResult("BODY-SIZE",
        cn.luwneshacha.thesis.rules.Applicability.APPLICABLE,
        cn.luwneshacha.thesis.rules.ExecutionStatus.EXECUTED,
        cn.luwneshacha.thesis.rules.Judgement.FAIL, "24", "20", "SIZE_MISMATCH", textBox.source());
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
  void preservesManualLineBreakAsBlankLineEvidence() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:br/></w:r></w:p>"
            + "<w:sectPr/></w:body></w:document>"));
    Path file = temp.resolve("manual-line-break.docx");
    pkg.save(file.toFile());

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));

    assertEquals(List.of("\n"), document.paragraphs().get(0).runs().stream()
        .map(RunModel::text).toList());
  }

  @Test
  void excludesPageBreaksFromVisibleTextAndAssignsParagraphRunOffsets() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p>"
            + "<w:r><w:br w:type=\"page\"/></w:r>"
            + "<w:r><w:t>AB</w:t></w:r>"
            + "<w:r><w:br/></w:r>"
            + "<w:r><w:t>CD</w:t></w:r>"
            + "</w:p></w:body></w:document>"));
    Path file = temp.resolve("page-break-run-offsets.docx");
    pkg.save(file.toFile());

    List<RunModel> runs = new Docx4jDocumentParser().parse(new DocxReader().read(file))
        .paragraphs().get(0).runs();

    assertEquals(List.of("AB", "\n", "CD"), runs.stream().map(RunModel::text).toList());
    assertEquals(List.of(0, 2, 3), runs.stream().map(run -> run.source().start()).toList());
    assertEquals(List.of(2, 3, 5), runs.stream().map(run -> run.source().end()).toList());
  }

  @Test
  void readsReferencedHeaderTextFormattingAndEffectiveSection() throws Exception {
    RawDocx source = new DocxReader().read(fixture());
    String header = "<w:hdr " + NS + "><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>"
        + "<w:r><w:rPr><w:rFonts w:ascii=\"黑体\" w:hAnsi=\"黑体\" w:eastAsia=\"黑体\"/>"
        + "<w:sz w:val=\"21\"/></w:rPr><w:t>山东石油化工学院本科毕业设计（论文）</w:t></w:r>"
        + "</w:p></w:hdr>";

    ParsedDocument document = new Docx4jDocumentParser().parse(
        addHeader(source, "word/header1.xml", "rIdHeader", header));

    assertEquals(1, document.headers().size());
    var parsedHeader = document.headers().get(0);
    assertEquals("山东石油化工学院本科毕业设计（论文）", parsedHeader.text());
    assertEquals(Boolean.TRUE, parsedHeader.centered());
    assertEquals(List.of(1), parsedHeader.applicableSectionIndexes());
    assertEquals("黑体", parsedHeader.runFormatting().get(0).asciiFont());
    assertEquals("黑体", parsedHeader.runFormatting().get(0).eastAsiaFont());
    assertEquals(21, parsedHeader.runFormatting().get(0).sizeHalfPoints());
    assertEquals(List.of(1), parsedHeader.applicableSectionIndexesByType().get(HeaderModel.Type.DEFAULT));
    assertFalse(document.sections().get(1).firstPageHeaderEnabled());
    assertFalse(document.sections().get(1).evenPageHeaderEnabled());
  }

  @Test
  void headerRunSourcesPointToActualRunsWhenParagraphPropertiesAndMultipleRunsExist() throws Exception {
    RawDocx source = new DocxReader().read(fixture());
    String header = "<w:hdr " + NS + "><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>"
        + "<w:r><w:t>first</w:t></w:r><w:r><w:t>second</w:t></w:r>"
        + "</w:p></w:hdr>";

    var parsed = new Docx4jDocumentParser().parse(
        addHeader(source, "word/header1.xml", "rIdHeader", header)).headers().get(0);

    assertEquals(List.of(0, 1), parsed.runFormatting().get(0).source().elementPath());
    assertEquals(List.of(0, 2), parsed.runFormatting().get(1).source().elementPath());
  }

  @Test
  void resolvesHeaderFormattingInRunParagraphAndDocumentDefaultPrecedence() throws Exception {
    RawDocx source = new DocxReader().read(fixture());
    var parts = new LinkedHashMap<>(source.parts());
    String styles = "<w:styles " + NS + "><w:docDefaults><w:rPrDefault><w:rPr>"
        + "<w:rFonts w:ascii=\"DefaultLatin\" w:hAnsi=\"DefaultLatin\" w:eastAsia=\"DefaultEast\"/>"
        + "<w:sz w:val=\"18\"/></w:rPr></w:rPrDefault></w:docDefaults>"
        + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>"
        + "<w:rPr><w:rFonts w:ascii=\"ParagraphLatin\" w:hAnsi=\"ParagraphLatin\" w:eastAsia=\"ParagraphEast\"/>"
        + "<w:sz w:val=\"20\"/></w:rPr></w:style>"
        + "<w:style w:type=\"paragraph\" w:styleId=\"Bare\"><w:name w:val=\"Bare\"/></w:style>"
        + "<w:style w:type=\"character\" w:styleId=\"HeaderCharacter\"><w:name w:val=\"Header Character\"/>"
        + "<w:rPr><w:rFonts w:ascii=\"RunLatin\" w:hAnsi=\"RunLatin\" w:eastAsia=\"RunEast\"/>"
        + "<w:sz w:val=\"22\"/></w:rPr></w:style></w:styles>";
    parts.put("word/styles.xml", new RawPart("word/styles.xml", "test",
        styles.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    RawDocx withStyles = new RawDocx(source.sourceSha256(), source.sourceBytes(), parts, source.entryCount());
    String header = "<w:hdr " + NS + "><w:p><w:r><w:t>paragraph style</w:t></w:r></w:p>"
        + "<w:p><w:pPr><w:pStyle w:val=\"Bare\"/></w:pPr><w:r><w:t>document default</w:t></w:r></w:p>"
        + "<w:p><w:pPr><w:pStyle w:val=\"Bare\"/></w:pPr><w:r><w:rPr><w:rStyle w:val=\"HeaderCharacter\"/></w:rPr>"
        + "<w:t>run style</w:t></w:r></w:p>"
        + "<w:p><w:pPr><w:pStyle w:val=\"Bare\"/></w:pPr><w:r><w:rPr>"
        + "<w:rFonts w:ascii=\"DirectLatin\" w:hAnsi=\"DirectLatin\" w:eastAsia=\"DirectEast\"/>"
        + "<w:sz w:val=\"24\"/></w:rPr><w:t>direct format</w:t></w:r></w:p></w:hdr>";

    var parsed = new Docx4jDocumentParser().parse(
        addHeader(withStyles, "word/header1.xml", "rIdHeader", header)).headers().get(0);

    assertEquals("ParagraphLatin", parsed.runFormatting().get(0).asciiFont());
    assertEquals("ParagraphEast", parsed.runFormatting().get(0).eastAsiaFont());
    assertEquals(20, parsed.runFormatting().get(0).sizeHalfPoints());
    assertEquals("DefaultLatin", parsed.runFormatting().get(1).asciiFont());
    assertEquals("DefaultEast", parsed.runFormatting().get(1).eastAsiaFont());
    assertEquals(18, parsed.runFormatting().get(1).sizeHalfPoints());
    assertEquals("RunLatin", parsed.runFormatting().get(2).asciiFont());
    assertEquals("RunEast", parsed.runFormatting().get(2).eastAsiaFont());
    assertEquals(22, parsed.runFormatting().get(2).sizeHalfPoints());
    assertEquals("DirectLatin", parsed.runFormatting().get(3).asciiFont());
    assertEquals("DirectEast", parsed.runFormatting().get(3).eastAsiaFont());
    assertEquals(24, parsed.runFormatting().get(3).sizeHalfPoints());
  }

  @Test
  void resolvesFirstAndEvenHeaderTypesAndSectionInheritance() throws Exception {
    RawDocx source = new DocxReader().read(fixture());
    String defaultHeader = "<w:hdr " + NS + "><w:p><w:r><w:t>DEFAULT</w:t></w:r></w:p></w:hdr>";
    String firstHeader = "<w:hdr " + NS + "><w:p><w:r><w:t>FIRST</w:t></w:r></w:p></w:hdr>";
    String evenHeader = "<w:hdr " + NS + "><w:p><w:r><w:t>EVEN</w:t></w:r></w:p></w:hdr>";
    RawDocx withDefault = addHeader(source, "word/header1.xml", "rIdHeaderDefault", "default", defaultHeader, true);
    RawDocx withFirst = addHeader(withDefault, "word/header2.xml", "rIdHeaderFirst", "first", firstHeader, true);
    RawDocx withEven = addHeader(withFirst, "word/header3.xml", "rIdHeaderEven", "even", evenHeader, true);
    var parts = new LinkedHashMap<>(withEven.parts());
    String documentXml = new String(parts.get("word/document.xml").bytes(), java.nio.charset.StandardCharsets.UTF_8);
    int finalSectionEnd = documentXml.lastIndexOf("</w:sectPr>");
    assertTrue(finalSectionEnd >= 0);
    documentXml = documentXml.substring(0, finalSectionEnd) + "<w:titlePg/>"
        + documentXml.substring(finalSectionEnd);
    parts.put("word/document.xml", new RawPart("word/document.xml", "test",
        documentXml.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    String settings = "<w:settings " + NS + "><w:evenAndOddHeaders/></w:settings>";
    parts.put("word/settings.xml", new RawPart("word/settings.xml", "test",
        settings.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    RawDocx enabled = new RawDocx(withEven.sourceSha256(), withEven.sourceBytes(), parts, withEven.entryCount());

    ParsedDocument parsed = new Docx4jDocumentParser().parse(enabled);

    assertTrue(parsed.sections().get(1).firstPageHeaderEnabled());
    assertTrue(parsed.sections().get(1).evenPageHeaderEnabled());
    assertEquals(List.of(1), parsed.headers().stream()
        .filter(header -> header.text().equals("FIRST")).findFirst().orElseThrow()
        .applicableSectionIndexesByType().get(HeaderModel.Type.FIRST));
    assertEquals(List.of(0, 1), parsed.headers().stream()
        .filter(header -> header.text().equals("DEFAULT")).findFirst().orElseThrow()
        .applicableSectionIndexesByType().get(HeaderModel.Type.DEFAULT));
    assertEquals(List.of(0, 1), parsed.headers().stream()
        .filter(header -> header.text().equals("EVEN")).findFirst().orElseThrow()
        .applicableSectionIndexesByType().get(HeaderModel.Type.EVEN));
  }

  @Test
  void ignoresIncludePictureFieldInstructionsAsParagraphText() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:pPr><w:ind w:firstLine=\"0\"/></w:pPr>"
            + "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
            + "<w:r><w:instrText xml:space=\"preserve\"> INCLUDEPICTURE \\\"image1.png\\\" </w:instrText></w:r>"
            + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"
            + "<w:r><w:drawing/></w:r>"
            + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p>"
            + "<w:sectPr/></w:body></w:document>"));
    Path file = temp.resolve("include-picture-field.docx");
    pkg.save(file.toFile());

    ParsedDocument parsed = new Docx4jDocumentParser().parse(new DocxReader().read(file));

    assertTrue(parsed.paragraphs().get(0).runs().isEmpty());
  }

  @Test
  void anchorsBodySectionToLastParagraph() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:t>first</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t>last</w:t></w:r></w:p>"
            + "<w:sectPr><w:pgMar w:top=\"1400\" w:right=\"1474\" w:bottom=\"1474\" w:left=\"1474\"/></w:sectPr>"
            + "</w:body></w:document>"));
    Path file = temp.resolve("body-section-anchor.docx");
    pkg.save(file.toFile());

    ParsedDocument parsed = new Docx4jDocumentParser().parse(new DocxReader().read(file));

    assertEquals(parsed.paragraphs().get(1).source().elementPath(), parsed.sections().get(0).source().elementPath());
  }

  @Test
  void doesNotTreatHighAnsiFontAsAsciiFont() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:rPr><w:rFonts w:hAnsi=\"Arial\"/></w:rPr>"
            + "<w:t>ABC123</w:t></w:r></w:p><w:sectPr/></w:body></w:document>"));
    var themeRelationship = pkg.getMainDocumentPart().getRelationshipsPart().getRelationshipByType(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme");
    var themePart = (org.docx4j.openpackaging.parts.ThemePart) pkg.getMainDocumentPart()
        .getRelationshipsPart().getPart(themeRelationship);
    String expectedAscii = themePart.getFontFromTheme(org.docx4j.wml.STTheme.MINOR_ASCII);
    Path file = temp.resolve("hansi-only.docx");
    pkg.save(file.toFile());

    RunModel run = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(0).runs().get(0);

    assertEquals(expectedAscii, run.asciiFont());
    assertFalse("Arial".equals(run.asciiFont()));
    assertEquals("Arial", run.highAnsiFont());
  }

  @Test
  void doesNotTreatPageGutterAsOneOfTheFourRequiredMargins() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:t>body</w:t></w:r></w:p>"
            + "<w:sectPr><w:pgMar w:top=\"1474\" w:right=\"1474\" w:bottom=\"1474\" "
            + "w:left=\"1474\" w:gutter=\"120\"/></w:sectPr></w:body></w:document>"));
    Path file = temp.resolve("gutter.docx");
    pkg.save(file.toFile());

    var result = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(new Docx4jDocumentParser().parse(new DocxReader().read(file)),
            cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(check -> check.ruleId().equals("PAGE-01")).findFirst().orElseThrow();

    assertEquals(cn.luwneshacha.thesis.rules.Judgement.PASS, result.judgement());
  }

  @Test
  void failsClosedForSectionMarginThatOverflowsTheModelInteger() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:t>body</w:t></w:r></w:p>"
            + "<w:sectPr><w:pgMar w:top=\"2147483648\" w:right=\"1474\" w:bottom=\"1474\" w:left=\"1474\"/>"
            + "</w:sectPr></w:body></w:document>"));
    Path file = temp.resolve("overflow-margin.docx");
    pkg.save(file.toFile());

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));

    assertNull(document.sections().get(0).topMarginTwips());
    var result = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(check -> check.ruleId().equals("PAGE-01")).findFirst().orElseThrow();
    assertEquals(cn.luwneshacha.thesis.rules.Judgement.FAIL, result.judgement());
    assertEquals("MARGIN_INVALID", result.reasonCode());
  }

  @Test
  void failsClosedForPageNumberStartThatOverflowsTheModelInteger() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:t>第一章 引言</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t>正文</w:t></w:r></w:p>"
            + "<w:sectPr><w:pgNumType w:start=\"2147483648\"/></w:sectPr>"
            + "</w:body></w:document>"));
    Path file = temp.resolve("overflow-page-number-start.docx");
    pkg.save(file.toFile());

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));

    var result = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(check -> check.ruleId().equals("PAGE-04")
            && check.reasonCode().equals("PAGE_NUMBER_START_INVALID"))
        .findFirst().orElseThrow();
    assertEquals(cn.luwneshacha.thesis.rules.Judgement.FAIL, result.judgement());
  }

  @Test
  void rejectsExplicitInvalidPageNumberFormatInsteadOfTreatingItAsMissing() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:t>\u7b2c1\u7ae0  \u7eea\u8bba</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t>\u6b63\u6587</w:t></w:r></w:p>"
            + "<w:sectPr><w:pgNumType w:fmt=\"DECIMAL\"/></w:sectPr>"
            + "</w:body></w:document>"));
    Path file = temp.resolve("invalid-page-number-format.docx");
    pkg.save(file.toFile());
    rewriteDocumentXml(file, xml -> {
      String rewritten = xml.replaceAll("<w:pgNumType[^>]*/>", "<w:pgNumType w:fmt=\"DECIMAL\"/>");
      if (rewritten.equals(xml)) rewritten = xml.replace("</w:sectPr>",
          "<w:pgNumType w:fmt=\"DECIMAL\"/></w:sectPr>");
      return rewritten;
    });
    try (var zip = new java.util.zip.ZipFile(file.toFile())) {
      String xml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(xml.contains("fmt=\"DECIMAL\""), xml);
    }

    var document = new Docx4jDocumentParser().parse(new DocxReader().read(file));
    assertTrue(document.sections().get(0).pageNumberFormatInvalid());
    var result = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(check -> check.ruleId().equals("PAGE-04")
            && check.reasonCode().equals("PAGE_NUMBER_FORMAT_INVALID"))
        .findFirst().orElseThrow();
    assertEquals(cn.luwneshacha.thesis.rules.Judgement.FAIL, result.judgement());
  }

  private static void rewriteDocumentXml(Path file, java.util.function.UnaryOperator<String> rewrite)
      throws Exception {
    Path replacement = file.resolveSibling(file.getFileName() + ".rewrite");
    try (var input = new ZipInputStream(Files.newInputStream(file));
        var output = new ZipOutputStream(Files.newOutputStream(replacement))) {
      ZipEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        output.putNextEntry(new ZipEntry(entry.getName()));
        byte[] bytes = input.readAllBytes();
        if ("word/document.xml".equals(entry.getName())) {
          String xml = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
          bytes = rewrite.apply(xml).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        output.write(bytes);
        output.closeEntry();
      }
    }
    Files.move(replacement, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
  }

  @Test
  void failsClosedForOutOfRangeIndentAndLineSpacingValues() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:t>第一章 引言</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:ind w:firstLine=\"2147483648\"/>"
            + "<w:spacing w:line=\"2147483648\" w:lineRule=\"auto\"/></w:pPr>"
            + "<w:r><w:rPr><w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\" w:eastAsia=\"瀹嬩綋\"/>"
            + "<w:sz w:val=\"24\"/></w:rPr><w:t>body</w:t></w:r></w:p>"
            + "</w:body></w:document>"));
    Path file = temp.resolve("overflow-paragraph-values.docx");
    pkg.save(file.toFile());

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));
    ParagraphModel paragraph = document.paragraphs().get(1);

    assertNull(paragraph.firstLineIndentTwips());
    assertNull(paragraph.lineTwips());
    var results = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1());
    var indentResult = results.stream().filter(check -> check.ruleId().equals("BODY-INDENT"))
        .findFirst().orElseThrow();
    assertEquals(cn.luwneshacha.thesis.rules.Judgement.FAIL, indentResult.judgement());
    assertEquals("INDENT_EVIDENCE_INVALID", indentResult.reasonCode());
    var lineSpacingResult = results.stream().filter(check -> check.ruleId().equals("BODY-LINE"))
        .findFirst().orElseThrow();
    assertEquals(cn.luwneshacha.thesis.rules.Judgement.FAIL, lineSpacingResult.judgement());
    assertEquals("LINE_SPACING_INVALID", lineSpacingResult.reasonCode());
  }

  @Test
  void directInvalidLineSpacingCannotBeHiddenByAValidInheritedStyle() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:t>第一章 引言</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:pStyle w:val=\"Body\"/>"
            + "<w:spacing w:line=\"2147483648\" w:lineRule=\"auto\"/></w:pPr>"
            + "<w:r><w:rPr><w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\" w:eastAsia=\"宋体\"/>"
            + "<w:sz w:val=\"24\"/></w:rPr><w:t>正文</w:t></w:r>"
            + "</w:p><w:sectPr/></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:styleId=\"Body\">"
            + "<w:name w:val=\"Body\"/><w:pPr><w:spacing w:line=\"360\" w:lineRule=\"auto\"/></w:pPr>"
            + "</w:style></w:styles>"));
    Path file = temp.resolve("invalid-direct-line-spacing-with-valid-style.docx");
    pkg.save(file.toFile());

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));
    var paragraph = document.paragraphs().get(1);
    var result = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(check -> check.ruleId().equals("BODY-LINE")
            && check.source().elementPath().equals(paragraph.source().elementPath()))
        .findFirst().orElseThrow();

    assertEquals(cn.luwneshacha.thesis.rules.Judgement.FAIL, result.judgement());
    assertEquals("LINE_SPACING_INVALID", result.reasonCode());
  }

  @Test
  void treatsMissingParagraphLineSpacingAsWordDefaultSingleEvenWithDocumentGrid() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:t>第一章 引言</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:ind w:firstLine=\"480\"/></w:pPr>"
            + "<w:r><w:rPr><w:sz w:val=\"24\"/></w:rPr><w:t>未设置段落行距</w:t></w:r></w:p>"
            + "<w:sectPr><w:docGrid w:type=\"lines\" w:linePitch=\"312\"/></w:sectPr>"
            + "</w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
            + "<w:name w:val=\"Normal\"/><w:pPr><w:jc w:val=\"both\"/></w:pPr>"
            + "</w:style></w:styles>"));
    Path file = temp.resolve("missing-line-spacing-with-document-grid.docx");
    pkg.save(file.toFile());

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));
    ParagraphModel bodyParagraph = document.paragraphs().get(1);

    assertEquals(240, bodyParagraph.lineTwips());
    assertNull(bodyParagraph.lineRule());
    var result = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(check -> check.ruleId().equals("BODY-LINE")
            && check.source().elementPath().equals(bodyParagraph.source().elementPath()))
        .findFirst().orElseThrow();
    assertEquals(cn.luwneshacha.thesis.rules.Judgement.FAIL, result.judgement());
    assertEquals("LINE_SPACING_MISMATCH", result.reasonCode());
  }

  @Test
  void paragraphStyleLineSpacingOverridesItsBasedOnStyle() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:pPr><w:pStyle w:val=\"HeadingLike\"/></w:pPr>"
            + "<w:r><w:t>3.1.1 技术可行性</w:t></w:r></w:p>"
            + "<w:sectPr/></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:styleId=\"Base\">"
            + "<w:pPr><w:spacing w:line=\"360\" w:lineRule=\"auto\"/></w:pPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"HeadingLike\"><w:basedOn w:val=\"Base\"/>"
            + "<w:pPr><w:spacing w:line=\"288\" w:lineRule=\"auto\"/></w:pPr></w:style></w:styles>"));
    Path file = temp.resolve("paragraph-style-line-spacing-overrides-based-on.docx");
    pkg.save(file.toFile());

    ParagraphModel paragraph = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(0);

    assertEquals(288, paragraph.lineTwips());
    assertEquals("auto", paragraph.lineRule());
  }

  @Test
  void parsesParagraphBeforeAndAfterSpacingFromEffectiveProperties() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:pPr><w:spacing w:before=\"240\" w:after=\"120\" w:line=\"360\" w:lineRule=\"auto\"/></w:pPr>"
            + "<w:r><w:t>字段</w:t></w:r></w:p><w:sectPr/></w:body></w:document>"));
    Path file = temp.resolve("paragraph-before-after-spacing.docx");
    pkg.save(file.toFile());

    ParagraphModel paragraph = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(0);

    assertEquals(240, paragraph.beforeSpacingTwips());
    assertEquals(120, paragraph.afterSpacingTwips());
  }

  @Test
  void rejectsExplicitInvalidLineRuleEvenWhenDocx4jDropsTheInvalidEnum() throws Exception {
    Path file = lineRuleFixture("<w:spacing w:line=\"360\" w:lineRule=\"invalid\"/>",
        "<w:spacing w:line=\"360\" w:lineRule=\"auto\"/>");

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));
    ParagraphModel paragraph = document.paragraphs().get(1);
    var result = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(check -> check.ruleId().equals("BODY-LINE")
            && check.source().elementPath().equals(paragraph.source().elementPath()))
        .findFirst().orElseThrow();

    assertEquals("invalid", paragraph.lineRule());
    assertEquals(cn.luwneshacha.thesis.rules.Judgement.FAIL, result.judgement());
    assertEquals("LINE_SPACING_MISMATCH", result.reasonCode());
  }

  @Test
  void rejectsInvalidInheritedLineRuleButAcceptsOmittedLineRuleDefault() throws Exception {
    Path invalidStyleFile = lineRuleFixture("<w:spacing w:line=\"360\"/>",
        "<w:spacing w:line=\"360\" w:lineRule=\"invalid\"/>");
    ParsedDocument invalidStyleDocument = new Docx4jDocumentParser()
        .parse(new DocxReader().read(invalidStyleFile));
    ParagraphModel invalidParagraph = invalidStyleDocument.paragraphs().get(1);
    var invalidResult = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(invalidStyleDocument, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(check -> check.ruleId().equals("BODY-LINE")
            && check.source().elementPath().equals(invalidParagraph.source().elementPath()))
        .findFirst().orElseThrow();
    assertEquals("invalid", invalidParagraph.lineRule());
    assertEquals(cn.luwneshacha.thesis.rules.Judgement.FAIL, invalidResult.judgement());

    Path overriddenFile = lineRuleFixture(
        "<w:spacing w:line=\"360\" w:lineRule=\"auto\"/>",
        "<w:spacing w:line=\"360\" w:lineRule=\"invalid\"/>");
    ParsedDocument overriddenDocument = new Docx4jDocumentParser()
        .parse(new DocxReader().read(overriddenFile));
    ParagraphModel overriddenParagraph = overriddenDocument.paragraphs().get(1);
    var overriddenResult = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(overriddenDocument, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(check -> check.ruleId().equals("BODY-LINE")
            && check.source().elementPath().equals(overriddenParagraph.source().elementPath()))
        .findFirst().orElseThrow();
    assertEquals("auto", overriddenParagraph.lineRule());
    assertEquals(cn.luwneshacha.thesis.rules.Judgement.PASS, overriddenResult.judgement());

    Path omittedFile = lineRuleFixture("<w:spacing w:line=\"360\"/>", null);
    ParsedDocument omittedDocument = new Docx4jDocumentParser().parse(new DocxReader().read(omittedFile));
    ParagraphModel omittedParagraph = omittedDocument.paragraphs().get(1);
    var omittedResult = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(omittedDocument, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(check -> check.ruleId().equals("BODY-LINE")
            && check.source().elementPath().equals(omittedParagraph.source().elementPath()))
        .findFirst().orElseThrow();
    assertEquals(cn.luwneshacha.thesis.rules.Judgement.PASS, omittedResult.judgement());
  }

  private Path lineRuleFixture(String paragraphSpacing, String styleSpacing) throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    Path file = temp.resolve("line-rule-" + java.util.UUID.randomUUID() + ".docx");
    pkg.save(file.toFile());
    String style = styleSpacing == null ? "" : "<w:styles " + NS
        + "><w:style w:type=\"paragraph\" w:styleId=\"Body\"><w:name w:val=\"Body\"/>"
        + "<w:pPr>" + styleSpacing + "</w:pPr></w:style></w:styles>";
    String styleReference = styleSpacing == null ? "" : "<w:pStyle w:val=\"Body\"/>";
    String document = "<w:document " + NS + "><w:body>"
        + "<w:p><w:r><w:t>第一章 引言</w:t></w:r></w:p>"
        + "<w:p><w:pPr>" + styleReference + "<w:ind w:firstLine=\"480\"/>"
        + paragraphSpacing + "</w:pPr><w:r><w:rPr><w:rFonts w:ascii=\"Times New Roman\" "
        + "w:hAnsi=\"Times New Roman\" w:eastAsia=\"宋体\"/><w:sz w:val=\"24\"/>"
        + "</w:rPr><w:t>正文内容</w:t></w:r></w:p><w:sectPr/></w:body></w:document>";
    return replaceZipPart(file, "word/document.xml", document,
        styleSpacing == null ? null : style);
  }

  private Path replaceZipPart(Path source, String partName, String replacement,
      String stylesReplacement) throws Exception {
    Path target = temp.resolve("patched-" + java.util.UUID.randomUUID() + ".docx");
    try (var input = new java.util.zip.ZipFile(source.toFile());
        var output = new java.util.zip.ZipOutputStream(Files.newOutputStream(target))) {
      var entries = input.entries();
      while (entries.hasMoreElements()) {
        var entry = entries.nextElement();
        output.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
        byte[] bytes;
        if (partName.equals(entry.getName())) bytes = replacement.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        else if ("word/styles.xml".equals(entry.getName()) && stylesReplacement != null)
          bytes = stylesReplacement.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        else bytes = input.getInputStream(entry).readAllBytes();
        output.write(bytes);
        output.closeEntry();
      }
    }
    return target;
  }

  @Test
  void resolvesThemeFontSlotsToTheirExactThemeFontNames() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    var relationships = pkg.getMainDocumentPart().getRelationshipsPart();
    var themeRelationship = relationships.getRelationshipByType(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme");
    var themePart = (org.docx4j.openpackaging.parts.ThemePart) relationships.getPart(themeRelationship);
    String expectedAscii = themePart.getFontFromTheme(org.docx4j.wml.STTheme.MINOR_ASCII);
    String expectedHighAnsi = themePart.getFontFromTheme(org.docx4j.wml.STTheme.MAJOR_H_ANSI);
    var settingsRelationship = relationships.getRelationshipByType(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings");
    var settingsPart = (org.docx4j.openpackaging.parts.WordprocessingML.DocumentSettingsPart)
        relationships.getPart(settingsRelationship);
    var eastAsiaThemeFont = new org.docx4j.dml.FontCollection.Font();
    eastAsiaThemeFont.setScript("Hans");
    eastAsiaThemeFont.setTypeface("宋体");
    themePart.getFontScheme().getMinorFont().getFont().add(eastAsiaThemeFont);
    var themeFontLang = settingsPart.getJaxbElement().getThemeFontLang();
    if (themeFontLang == null) {
      themeFontLang = new org.docx4j.wml.CTLanguage();
      settingsPart.getJaxbElement().setThemeFontLang(themeFontLang);
    }
    themeFontLang.setEastAsia("zh-CN");
    String expectedEastAsia = themePart.getFont(org.docx4j.wml.STTheme.MINOR_EAST_ASIA, themeFontLang);
    assertTrue(expectedEastAsia != null && !expectedEastAsia.isBlank());
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:rPr><w:rFonts "
            + "w:asciiTheme=\"minorAscii\" w:hAnsiTheme=\"majorHAnsi\" "
            + "w:eastAsiaTheme=\"minorEastAsia\"/></w:rPr>"
            + "<w:t>ABCé中</w:t></w:r></w:p><w:sectPr/></w:body></w:document>"));
    Path file = temp.resolve("theme-fonts.docx");
    pkg.save(file.toFile());

    RunModel run = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(0).runs().get(0);

    assertEquals(expectedAscii, run.asciiFont());
    assertEquals(expectedHighAnsi, run.highAnsiFont());
    assertEquals(expectedEastAsia, run.eastAsiaFont());
  }

  @Test
  void themeFontTakesPrecedenceOverConflictingExplicitFontName() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    var relationships = pkg.getMainDocumentPart().getRelationshipsPart();
    var themeRelationship = relationships.getRelationshipByType(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme");
    var themePart = (org.docx4j.openpackaging.parts.ThemePart) relationships.getPart(themeRelationship);
    String expectedAscii = themePart.getFontFromTheme(org.docx4j.wml.STTheme.MINOR_ASCII);
    var settingsRelationship = relationships.getRelationshipByType(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings");
    var settingsPart = (org.docx4j.openpackaging.parts.WordprocessingML.DocumentSettingsPart)
        relationships.getPart(settingsRelationship);
    var themeFontLang = settingsPart.getJaxbElement().getThemeFontLang();
    if (themeFontLang == null) {
      themeFontLang = new org.docx4j.wml.CTLanguage();
      settingsPart.getJaxbElement().setThemeFontLang(themeFontLang);
    }
    themeFontLang.setEastAsia("zh-CN");
    var eastAsiaFont = new org.docx4j.dml.FontCollection.Font();
    eastAsiaFont.setScript("Hans");
    eastAsiaFont.setTypeface("宋体");
    themePart.getFontScheme().getMinorFont().getFont().add(eastAsiaFont);
    String expectedEastAsia = themePart.getFont(
        org.docx4j.wml.STTheme.MINOR_EAST_ASIA, themeFontLang);
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:rPr><w:rFonts "
            + "w:ascii=\"Wrong Explicit Font\" w:asciiTheme=\"minorAscii\" "
            + "w:eastAsia=\"错误字体\" w:eastAsiaTheme=\"minorEastAsia\"/>"
            + "</w:rPr><w:t>文字ABC</w:t></w:r></w:p><w:sectPr/></w:body></w:document>"));
    Path file = temp.resolve("conflicting-theme-fonts.docx");
    pkg.save(file.toFile());
    try (var zip = new java.util.zip.ZipFile(file.toFile())) {
      String documentXml = new String(zip.getInputStream(zip.getEntry("word/document.xml")).readAllBytes(),
          java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(documentXml.contains("ascii=\"Wrong Explicit Font\""));
      assertTrue(documentXml.contains("asciiTheme=\"minorAscii\""));
      assertTrue(documentXml.contains("eastAsia=\"错误字体\""));
      assertTrue(documentXml.contains("eastAsiaTheme=\"minorEastAsia\""));
    }

    RunModel run = new Docx4jDocumentParser().parse(new DocxReader().read(file))
        .paragraphs().get(0).runs().get(0);

    assertEquals(expectedAscii, run.asciiFont());
    assertEquals(expectedEastAsia, run.eastAsiaFont());
  }

  @Test
  void resolvesComplexScriptThemeFontToItsExactThemeFontName() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    var relationships = pkg.getMainDocumentPart().getRelationshipsPart();
    var themeRelationship = relationships.getRelationshipByType(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme");
    var themePart = (org.docx4j.openpackaging.parts.ThemePart) relationships.getPart(themeRelationship);
    themePart.getFontScheme().getMinorFont().getCs().setTypeface("Arial");
    var settingsRelationship = relationships.getRelationshipByType(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings");
    var settingsPart = (org.docx4j.openpackaging.parts.WordprocessingML.DocumentSettingsPart)
        relationships.getPart(settingsRelationship);
    var themeFontLang = settingsPart.getJaxbElement().getThemeFontLang();
    if (themeFontLang == null) {
      themeFontLang = new org.docx4j.wml.CTLanguage();
      settingsPart.getJaxbElement().setThemeFontLang(themeFontLang);
    }
    themeFontLang.setBidi("ar-SA");
    String expectedComplex = themePart.getFont(org.docx4j.wml.STTheme.MINOR_BIDI, themeFontLang);
    assertEquals("Arial", expectedComplex);
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:rPr><w:rFonts w:csTheme=\"minorBidi\"/>"
            + "</w:rPr><w:t>العربية</w:t></w:r></w:p>"
            + "<w:p><w:r><w:rPr><w:rFonts w:cs=\"Times New Roman\" w:csTheme=\"minorBidi\"/>"
            + "</w:rPr><w:t>العربية</w:t></w:r></w:p><w:sectPr/></w:body></w:document>"));
    Path file = temp.resolve("complex-theme-font.docx");
    pkg.save(file.toFile());

    var document = new Docx4jDocumentParser().parse(new DocxReader().read(file));
    RunModel run = document.paragraphs().get(0).runs().get(0);
    RunModel directOverride = document.paragraphs().get(1).runs().get(0);

    assertEquals(expectedComplex, run.complexFont());
    assertEquals("Times New Roman", directOverride.complexFont());
  }

  @Test
  void preservesExactAutomaticNumberingSeparatorEvidence() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:pPr><w:outlineLvl w:val=\"0\"/><w:numPr>"
            + "<w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>"
            + "<w:r><w:t xml:space=\"preserve\"> 标题</w:t></w:r></w:p></w:body></w:document>"));
    var numberingPart = new org.docx4j.openpackaging.parts.WordprocessingML.NumberingDefinitionsPart();
    numberingPart.setJaxbElement((org.docx4j.wml.Numbering) XmlUtils.unmarshalString(
        "<w:numbering " + NS + "><w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\">"
            + "<w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"第%1章\"/>"
            + "<w:suff w:val=\"space\"/></w:lvl></w:abstractNum><w:num w:numId=\"1\">"
            + "<w:abstractNumId w:val=\"0\"/></w:num></w:numbering>"));
    pkg.getMainDocumentPart().addTargetPart(numberingPart);
    Path file = temp.resolve("automatic-heading.docx");
    pkg.save(file.toFile());

    ParagraphModel heading = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(0);

    assertEquals("第1章", heading.numberingLabel());
    assertEquals(1, heading.numberingSeparatorSpaces());
    assertEquals(0, heading.numberingFormatTrailingSpaces());
    var spacing = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(new ParsedDocument("source", List.of(heading), List.of(), List.of()),
            cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("HEAD-01-SPACING")).findFirst().orElseThrow();
    assertEquals(cn.luwneshacha.thesis.rules.Judgement.PASS, spacing.judgement());
  }

  @Test
  void inheritsAutomaticNumberingSpacingWhenOverrideOnlyChangesStartValue() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:pPr><w:outlineLvl w:val=\"0\"/><w:numPr>"
            + "<w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>"
            + "<w:r><w:t>标题</w:t></w:r></w:p></w:body></w:document>"));
    var numberingPart = new org.docx4j.openpackaging.parts.WordprocessingML.NumberingDefinitionsPart();
    numberingPart.setJaxbElement((org.docx4j.wml.Numbering) XmlUtils.unmarshalString(
        "<w:numbering " + NS + "><w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\">"
            + "<w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"%1\"/><w:suff w:val=\"space\"/>"
            + "</w:lvl></w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/>"
            + "<w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"7\"/></w:lvlOverride>"
            + "</w:num></w:numbering>"));
    pkg.getMainDocumentPart().addTargetPart(numberingPart);
    Path file = temp.resolve("automatic-heading-start-override.docx");
    pkg.save(file.toFile());

    ParagraphModel heading = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(0);

    assertEquals(1, heading.numberingSeparatorSpaces());
    assertEquals(0, heading.numberingFormatTrailingSpaces());
  }

  @Test
  void resolvesAutomaticNumberingFromBasedOnStyleWithoutExplicitLevel() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:pPr><w:pStyle w:val=\"RefDerived\"/></w:pPr>"
            + "<w:r><w:t>参考文献条目</w:t></w:r></w:p></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + ">"
            + "<w:style w:type=\"paragraph\" w:styleId=\"RefDerived\">"
            + "<w:basedOn w:val=\"RefBase\"/></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"RefBase\">"
            + "<w:pPr><w:numPr><w:numId w:val=\"1\"/></w:numPr></w:pPr>"
            + "</w:style></w:styles>"));
    var numberingPart = new org.docx4j.openpackaging.parts.WordprocessingML.NumberingDefinitionsPart();
    numberingPart.setJaxbElement((org.docx4j.wml.Numbering) XmlUtils.unmarshalString(
        "<w:numbering " + NS + "><w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\">"
            + "<w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"[%1]\"/>"
            + "</w:lvl></w:abstractNum><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/>"
            + "</w:num></w:numbering>"));
    pkg.getMainDocumentPart().addTargetPart(numberingPart);
    Path file = temp.resolve("style-inherited-numbering-without-ilvl.docx");
    pkg.save(file.toFile());

    ParagraphModel paragraph = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(0);

    assertTrue(paragraph.automaticNumberingActive());
    assertEquals("[1]", paragraph.numberingLabel());
  }

  @Test
  void marksAutomaticNumberingWithUnknownDefinitionAsUnverifiable() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:pPr><w:outlineLvl w:val=\"0\"/><w:numPr>"
            + "<w:ilvl w:val=\"0\"/><w:numId w:val=\"99\"/></w:numPr></w:pPr>"
            + "<w:r><w:t>第一章  引言</w:t></w:r></w:p></w:body></w:document>"));
    var numberingPart = new org.docx4j.openpackaging.parts.WordprocessingML.NumberingDefinitionsPart();
    numberingPart.setJaxbElement((org.docx4j.wml.Numbering) XmlUtils.unmarshalString(
        "<w:numbering " + NS + "><w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/>"
            + "</w:num></w:numbering>"));
    pkg.getMainDocumentPart().addTargetPart(numberingPart);
    Path file = temp.resolve("automatic-heading-unknown-numbering.docx");
    pkg.save(file.toFile());

    ParagraphModel heading = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(0);
    var result = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(new ParsedDocument("source", List.of(heading), List.of(), List.of()),
            cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("HEAD-01-SPACING")).findFirst().orElseThrow();

    assertEquals(cn.luwneshacha.thesis.rules.Judgement.FAIL, result.judgement());
    assertEquals("HEADING_NUMBER_SPACING_MISMATCH", result.reasonCode());
  }

  @Test
  void treatsNumberingIdZeroAsDisabledAndChecksLiteralHeadingSpacing() throws Exception {
    ParagraphModel validHeading = parseHeadingWithNumberingIdZero("第一章  引言");
    ParagraphModel invalidHeading = parseHeadingWithNumberingIdZero("第一章 引言");

    var engine = new cn.luwneshacha.thesis.rules.RuleEngine();
    var valid = engine.check(new ParsedDocument("valid", List.of(validHeading), List.of(), List.of()),
        cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("HEAD-01-SPACING")).findFirst().orElseThrow();
    var invalid = engine.check(new ParsedDocument("invalid", List.of(invalidHeading), List.of(), List.of()),
        cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("HEAD-01-SPACING")).findFirst().orElseThrow();

    assertEquals(cn.luwneshacha.thesis.rules.Judgement.PASS, valid.judgement());
    assertEquals(cn.luwneshacha.thesis.rules.Judgement.FAIL, invalid.judgement());
  }

  private ParagraphModel parseHeadingWithNumberingIdZero(String text) throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:pPr><w:outlineLvl w:val=\"0\"/><w:numPr>"
            + "<w:ilvl w:val=\"0\"/><w:numId w:val=\"0\"/></w:numPr></w:pPr>"
            + "<w:r><w:t xml:space=\"preserve\">" + text + "</w:t></w:r></w:p></w:body></w:document>"));
    Path file = temp.resolve("automatic-numbering-disabled-" + text.hashCode() + ".docx");
    pkg.save(file.toFile());
    return new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(0);
  }

  @Test
  void preservesTabCharactersInRunTextForTableOfContentsParsing() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:t>第一章  引言</w:t><w:tab/></w:r>"
            + "<w:fldSimple w:instr=\" PAGEREF _Toc1 \"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p>"
            + "</w:body></w:document>"));
    Path file = temp.resolve("toc-tab.docx");
    pkg.save(file.toFile());

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));

    assertEquals("第一章  引言\t1", document.paragraphs().get(0).runs().stream()
        .map(RunModel::text).reduce("", String::concat));
    var pageFinding = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(new ParsedDocument("toc-field", List.of(
            new ParagraphModel(List.of(new RunModel("目    录", "Times New Roman", "黑体", null, 32,
                false, document.paragraphs().get(0).source())), 0, null, null, null, 360, "auto",
                document.paragraphs().get(0).source(), 0, null, "center"),
            new ParagraphModel(List.of(new RunModel("", "Times New Roman", "宋体", null, 24, false,
                document.paragraphs().get(0).source())), 0, null, null, null, 360, "auto",
                document.paragraphs().get(0).source(), 0, null, "left"),
            document.paragraphs().get(0)), List.of(), List.of()), cn.luwneshacha.thesis.rules.RuleSet.schoolV1())
        .stream().filter(result -> result.ruleId().equals("TOC-ENTRY-PAGE"))
        .findFirst().orElseThrow();
    assertEquals(cn.luwneshacha.thesis.rules.Judgement.PASS, pageFinding.judgement());
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
    assertEquals(List.of(1), document.pageNumbers().get(0).applicableSectionIndexes());
    var pageNumberJson = new com.fasterxml.jackson.databind.ObjectMapper()
        .valueToTree(document.pageNumbers().get(0));
    assertFalse(pageNumberJson.has("applicableSectionIndexes"));
  }

  @Test
  void checksEveryRunInThePageFieldResult() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    String footer = "<w:ftr " + NS + "><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>"
        + "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r>"
        + "<w:r><w:instrText> PAGE </w:instrText></w:r>"
        + "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r>"
        + "<w:r><w:rPr><w:rFonts w:ascii=\"Times New Roman\"/><w:sz w:val=\"21\"/></w:rPr><w:t>1</w:t></w:r>"
        + "<w:r><w:rPr><w:rFonts w:ascii=\"Arial\"/><w:sz w:val=\"22\"/></w:rPr><w:t>2</w:t></w:r>"
        + "<w:r><w:fldChar w:fldCharType=\"end\"/></w:r></w:p></w:ftr>";
    RawDocx withFooter = addFooter(valid, "word/footer-split-result.xml", "rIdFooterSplitResult", footer);

    ParsedDocument document = new Docx4jDocumentParser().parse(withFooter);
    assertEquals(1, document.pageNumbers().size());
    assertEquals(2, document.pageNumbers().get(0).runFormatting().size());
    assertEquals(new PageNumberModel.RunFormatting("Times New Roman", 21),
        document.pageNumbers().get(0).runFormatting().get(0));
    assertEquals(new PageNumberModel.RunFormatting("Arial", 22),
        document.pageNumbers().get(0).runFormatting().get(1));
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
    assertTrue(document.pageNumbers().stream().anyMatch(page -> page.source().partName().equals("word/footer1.xml")
        && page.footerVariant() == PageNumberModel.FooterVariant.DEFAULT));
    assertTrue(document.pageNumbers().stream().anyMatch(page -> page.source().partName().equals("word/footer2.xml")
        && page.footerVariant() == PageNumberModel.FooterVariant.EVEN));
  }

  @Test
  void preservesFirstPageFooterVariantIdentityWhenTitlePageIsEnabled() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    String page = "<w:ftr " + NS + "><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>"
        + "<w:r><w:instrText> PAGE </w:instrText></w:r></w:p></w:ftr>";
    RawDocx withDefault = addFooter(valid, "word/footer-default.xml", "rIdFooterDefault", "default", page);
    RawDocx withFirst = addFooter(withDefault, "word/footer-first.xml", "rIdFooterFirst", "first", page);
    var parts = new LinkedHashMap<>(withFirst.parts());
    String documentXml = new String(parts.get("word/document.xml").bytes(), java.nio.charset.StandardCharsets.UTF_8);
    int finalSectionEnd = documentXml.lastIndexOf("</w:sectPr>");
    assertTrue(finalSectionEnd >= 0);
    documentXml = documentXml.substring(0, finalSectionEnd) + "<w:titlePg/>"
        + documentXml.substring(finalSectionEnd);
    parts.put("word/document.xml", new RawPart("word/document.xml", "test",
        documentXml.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    RawDocx enabled = new RawDocx(withFirst.sourceSha256(), withFirst.sourceBytes(), parts, withFirst.entryCount());

    ParsedDocument parsed = new Docx4jDocumentParser().parse(enabled);

    assertTrue(parsed.pageNumbers().stream().anyMatch(pageNumber ->
        pageNumber.source().partName().equals("word/footer-default.xml")
            && pageNumber.footerVariant() == PageNumberModel.FooterVariant.DEFAULT));
    assertTrue(parsed.pageNumbers().stream().anyMatch(pageNumber ->
        pageNumber.source().partName().equals("word/footer-first.xml")
            && pageNumber.footerVariant() == PageNumberModel.FooterVariant.FIRST));
  }

  @Test
  void rejectsEvenPageFooterWhenOnOffValueIsExplicitlyInvalid() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    String centeredPage = "<w:ftr " + NS + "><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>"
        + "<w:r><w:instrText> PAGE </w:instrText></w:r></w:p></w:ftr>";
    String uncenteredEvenPage = "<w:ftr " + NS + "><w:p><w:pPr><w:jc w:val=\"left\"/></w:pPr>"
        + "<w:r><w:instrText> PAGE </w:instrText></w:r></w:p></w:ftr>";
    RawDocx withDefault = addFooter(valid, "word/footer1.xml", "rIdFooter1", "default", centeredPage);
    RawDocx withEven = addFooter(withDefault, "word/footer2.xml", "rIdFooter2", "even", uncenteredEvenPage);
    var parts = new LinkedHashMap<>(withEven.parts());
    String settings = "<w:settings " + NS + "><w:evenAndOddHeaders w:val=\"maybe\"/></w:settings>";
    parts.put("word/settings.xml", new RawPart("word/settings.xml", "test",
        settings.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    RawDocx invalidSetting = new RawDocx(withEven.sourceSha256(), withEven.sourceBytes(), parts, withEven.entryCount());

    org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
        () -> new Docx4jDocumentParser().parse(invalidSetting));
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
  void resolvesPageNumberThemeFontBeforeConflictingExplicitFont() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    var pkg = WordprocessingMLPackage.load(new java.io.ByteArrayInputStream(valid.sourceBytes()));
    var relationship = pkg.getMainDocumentPart().getRelationshipsPart().getRelationshipByType(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme");
    var themePart = (org.docx4j.openpackaging.parts.ThemePart)
        pkg.getMainDocumentPart().getRelationshipsPart().getPart(relationship);
    String expected = themePart.getFontFromTheme(org.docx4j.wml.STTheme.MAJOR_ASCII);
    var parts = new LinkedHashMap<>(valid.parts());
    String footer = "<w:ftr " + NS + "><w:p><w:r><w:instrText> PAGE </w:instrText></w:r>"
        + "<w:r><w:rPr><w:rFonts w:ascii=\"Arial\" w:asciiTheme=\"majorAscii\"/></w:rPr>"
        + "<w:t>1</w:t></w:r></w:p></w:ftr>";
    parts.put("word/footer-theme.xml", new RawPart("word/footer-theme.xml", "test",
        footer.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    RawDocx withFooter = addFooter(new RawDocx(valid.sourceSha256(), valid.sourceBytes(), parts, valid.entryCount()),
        "word/footer-theme.xml", "rIdFooterTheme", footer);

    PageNumberModel pageNumber = new Docx4jDocumentParser().parse(withFooter).pageNumbers().get(0);

    assertEquals(expected, pageNumber.asciiFont());
  }

  @Test
  void inheritsPageNumberAsciiFontWhenDerivedCharacterStyleOnlyOverridesEastAsia() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    var parts = new LinkedHashMap<>(valid.parts());
    String footer = "<w:ftr " + NS + "><w:p><w:r><w:instrText> PAGE </w:instrText></w:r>"
        + "<w:r><w:rPr><w:rStyle w:val=\"PageNumber\"/></w:rPr><w:t>1</w:t></w:r></w:p></w:ftr>";
    String styles = "<w:styles " + NS + "><w:style w:type=\"character\" w:styleId=\"BasePage\">"
        + "<w:rPr><w:rFonts w:ascii=\"Times New Roman\"/></w:rPr></w:style>"
        + "<w:style w:type=\"character\" w:styleId=\"PageNumber\"><w:basedOn w:val=\"BasePage\"/>"
        + "<w:rPr><w:rFonts w:eastAsia=\"宋体\"/></w:rPr></w:style></w:styles>";
    parts.put("word/footer-inherited-font.xml", new RawPart("word/footer-inherited-font.xml", "test",
        footer.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    parts.put("word/styles.xml", new RawPart("word/styles.xml", "test",
        styles.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    RawDocx withFooter = addFooter(new RawDocx(valid.sourceSha256(), valid.sourceBytes(), parts, valid.entryCount()),
        "word/footer-inherited-font.xml", "rIdFooterInheritedFont", footer);

    PageNumberModel pageNumber = new Docx4jDocumentParser().parse(withFooter).pageNumbers().get(0);

    assertEquals("Times New Roman", pageNumber.asciiFont());
  }

  @Test
  void doesNotFallBackToConflictingExplicitPageFontWhenThemeReferenceIsInvalid() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    var parts = new LinkedHashMap<>(valid.parts());
    String footer = "<w:ftr " + NS + "><w:p><w:r><w:instrText> PAGE </w:instrText></w:r>"
        + "<w:r><w:rPr><w:rFonts w:ascii=\"Arial\" w:asciiTheme=\"notATheme\"/></w:rPr>"
        + "<w:t>1</w:t></w:r></w:p></w:ftr>";
    parts.put("word/footer-invalid-theme.xml", new RawPart("word/footer-invalid-theme.xml", "test",
        footer.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    RawDocx withFooter = addFooter(new RawDocx(valid.sourceSha256(), valid.sourceBytes(), parts, valid.entryCount()),
        "word/footer-invalid-theme.xml", "rIdFooterInvalidTheme", footer);

    PageNumberModel pageNumber = new Docx4jDocumentParser().parse(withFooter).pageNumbers().get(0);

    assertNull(pageNumber.asciiFont());
  }

  @Test
  void doesNotFallBackToParagraphFontWhenDirectPageThemeFontIsInvalid() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    var parts = new LinkedHashMap<>(valid.parts());
    String footer = "<w:ftr " + NS + "><w:p><w:pPr><w:pStyle w:val=\"Footer\"/></w:pPr>"
        + "<w:r><w:instrText> PAGE </w:instrText></w:r>"
        + "<w:r><w:rPr><w:rFonts w:ascii=\"Arial\" w:asciiTheme=\"notATheme\"/></w:rPr>"
        + "<w:t>1</w:t></w:r></w:p></w:ftr>";
    String styles = "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:styleId=\"Footer\">"
        + "<w:rPr><w:rFonts w:ascii=\"Times New Roman\"/></w:rPr></w:style></w:styles>";
    parts.put("word/footer-invalid-direct-theme.xml", new RawPart("word/footer-invalid-direct-theme.xml", "test",
        footer.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    parts.put("word/styles.xml", new RawPart("word/styles.xml", "test",
        styles.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    RawDocx withFooter = addFooter(new RawDocx(valid.sourceSha256(), valid.sourceBytes(), parts, valid.entryCount()),
        "word/footer-invalid-direct-theme.xml", "rIdFooterInvalidDirectTheme", footer);

    PageNumberModel pageNumber = new Docx4jDocumentParser().parse(withFooter).pageNumbers().get(0);

    assertNull(pageNumber.asciiFont());
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
  void readsPageNumberFormattingFromDocDefaultsXmlNesting() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    var parts = new LinkedHashMap<>(valid.parts());
    String footer = "<w:ftr " + NS + "><w:p><w:r><w:instrText> PAGE </w:instrText></w:r>"
        + "<w:r><w:t>1</w:t></w:r></w:p></w:ftr>";
    String styles = "<w:styles " + NS + "><w:docDefaults>"
        + "<w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Times New Roman\"/><w:sz w:val=\"21\"/></w:rPr></w:rPrDefault>"
        + "<w:pPrDefault><w:pPr><w:jc w:val=\"center\"/></w:pPr></w:pPrDefault>"
        + "</w:docDefaults></w:styles>";
    parts.put("word/styles.xml", new RawPart("word/styles.xml", "test", styles.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    RawDocx withFooter = addFooter(new RawDocx(valid.sourceSha256(), valid.sourceBytes(), parts, valid.entryCount()),
        "word/footer7.xml", "rIdFooter7", footer);

    PageNumberModel page = new Docx4jDocumentParser().parse(withFooter).pageNumbers().get(0);

    assertEquals(Boolean.TRUE, page.centered());
    assertEquals("Times New Roman", page.asciiFont());
    assertEquals(21, page.sizeHalfPoints());
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
  void resolvesThemeFontForPageNumberRun() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    String footer = "<w:ftr " + NS + "><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>"
        + "<w:r><w:instrText> PAGE </w:instrText></w:r>"
        + "<w:r><w:rPr><w:rFonts w:asciiTheme=\"minorAscii\"/><w:sz w:val=\"21\"/></w:rPr>"
        + "<w:t>1</w:t></w:r></w:p></w:ftr>";
    RawDocx withFooter = addFooter(valid, "word/footer-theme.xml", "rIdFooterTheme", footer);
    var pkg = WordprocessingMLPackage.load(new java.io.ByteArrayInputStream(valid.sourceBytes()));
    var themeRelationship = pkg.getMainDocumentPart().getRelationshipsPart().getRelationshipByType(
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme");
    var themePart = (org.docx4j.openpackaging.parts.ThemePart) pkg.getMainDocumentPart()
        .getRelationshipsPart().getPart(themeRelationship);
    String expectedFont = themePart.getFontFromTheme(org.docx4j.wml.STTheme.MINOR_ASCII);

    PageNumberModel page = new Docx4jDocumentParser().parse(withFooter).pageNumbers().get(0);

    assertEquals(expectedFont, page.asciiFont());
  }

  @Test
  void doesNotInferPageNumberCenteringFromAParagraphInsideAFooterTable() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    String footer = "<w:ftr " + NS + "><w:tbl><w:tr><w:tc><w:p><w:pPr><w:jc w:val=\"left\"/></w:pPr>"
        + "<w:r><w:instrText> PAGE </w:instrText></w:r></w:p></w:tc></w:tr></w:tbl></w:ftr>";
    RawDocx withFooter = addFooter(valid, "word/footer1.xml", "rIdFooter1", footer);

    PageNumberModel pageNumber = new Docx4jDocumentParser().parse(withFooter).pageNumbers().get(0);

    assertEquals(null, pageNumber.centered());
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
  void doesNotLetInvalidDirectCharacterIndentFallBackToInheritedTwipsIndent() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:t>第一章 绪论</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:pStyle w:val=\"Body\"/><w:ind w:firstLineChars=\"2147483648\"/></w:pPr>"
            + "<w:r><w:t>直接字符缩进值超出当前解析器支持的整数范围</w:t></w:r></w:p>"
            + "<w:sectPr/></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:styleId=\"Body\"><w:name w:val=\"Body\"/>"
            + "<w:pPr><w:ind w:firstLine=\"480\"/></w:pPr></w:style></w:styles>"));
    Path file = temp.resolve("invalid-direct-character-indent-fallback.docx");
    pkg.save(file.toFile());

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));

    assertTrue(new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .anyMatch(result -> result.ruleId().equals("BODY-INDENT")
            && result.source().elementPath().equals(document.paragraphs().get(1).source().elementPath())
            && result.judgement() == cn.luwneshacha.thesis.rules.Judgement.FAIL));
  }

  @Test
  void keepsFirstLineIndentIndependentOfParagraphLeftIndent() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:t>第一章 绪论</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:ind w:firstLine=\"480\" w:left=\"120\"/></w:pPr>"
            + "<w:r><w:rPr><w:sz w:val=\"24\"/></w:rPr><w:t>首行值正确但另有左缩进</w:t></w:r></w:p>"
            + "<w:sectPr/></w:body></w:document>"));
    Path file = temp.resolve("first-line-with-extra-left-indent.docx");
    pkg.save(file.toFile());

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));

    var result = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(check -> check.ruleId().equals("BODY-INDENT")
            && check.source().elementPath().equals(document.paragraphs().get(1).source().elementPath()))
        .findFirst().orElseThrow();

    assertEquals(cn.luwneshacha.thesis.rules.Judgement.PASS, result.judgement());
    assertTrue(result.actual().contains("left=120 twips"));
  }

  @Test
  void doesNotTurnUnparsedHangingIndentIntoZero() throws Exception {
    var document = parseIndentEvidenceFixture("firstLineChars='200' hangingChars='2147483648'", false);
    assertTrue(new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .anyMatch(result -> result.ruleId().equals("BODY-INDENT")
            && result.judgement() == cn.luwneshacha.thesis.rules.Judgement.FAIL));
  }

  @Test
  void unparsedLeftIndentDoesNotInvalidateKnownFirstLineIndent() throws Exception {
    var document = parseIndentEvidenceFixture("firstLineChars='200' leftChars='2147483648'", false);
    var finding = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("BODY-INDENT")).findFirst().orElseThrow();
    assertEquals(cn.luwneshacha.thesis.rules.Judgement.PASS, finding.judgement());
  }

  @Test
  void overriddenTwipsCannotInvalidateEffectiveCharacterIndent() throws Exception {
    var document = parseIndentEvidenceFixture(
        "firstLineChars='200' firstLine='2147483648' hangingChars='0' hanging='480'", false);
    var finding = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(result -> result.ruleId().equals("BODY-INDENT")).findFirst().orElseThrow();
    assertEquals(cn.luwneshacha.thesis.rules.Judgement.PASS, finding.judgement());
    assertFalse(finding.actual().contains("hanging=480"));
  }

  @Test
  void characterIndentEvidenceHidesOverriddenTwipsForEveryIndentAxis() throws Exception {
    var document = parseIndentEvidenceFixture(
        "firstLineChars='200' firstLine='480' hangingChars='0' hanging='480' leftChars='100' left='480'", false);

    ParagraphModel paragraph = document.paragraphs().get(1);

    assertEquals(200, paragraph.firstLineChars());
    assertNull(paragraph.firstLineIndentTwips());
    assertEquals(0, paragraph.hangingIndentChars());
    assertNull(paragraph.hangingIndentTwips());
    assertEquals(100, paragraph.leftIndentChars());
    assertNull(paragraph.leftIndentTwips());
  }

  @Test
  void invalidCharacterIndentEvidenceDoesNotFallbackToInheritedTwips() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:t>第一章 绪论</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:pStyle w:val=\"Body\"/><w:ind w:firstLineChars=\"2147483648\"/></w:pPr>"
            + "<w:r><w:t>无效字符缩进</w:t></w:r></w:p>"
            + "<w:sectPr/></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:styleId=\"Body\"><w:name w:val=\"Body\"/>"
            + "<w:pPr><w:ind w:firstLine=\"480\"/></w:pPr></w:style></w:styles>"));
    Path file = temp.resolve("invalid-character-indent-evidence.docx");
    pkg.save(file.toFile());

    ParagraphModel paragraph = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(1);

    assertNull(paragraph.firstLineIndentTwips());
    assertTrue(paragraph.firstLineEvidenceInvalid());
  }

  @Test
  void missingIndentEvidenceFailsTheRequiredBodyIndent() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:t>第一章 绪论</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t>没有缩进属性</w:t></w:r></w:p>"
            + "<w:sectPr/></w:body></w:document>"));
    Path file = temp.resolve("missing-indent-evidence.docx");
    pkg.save(file.toFile());

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));
    ParagraphModel paragraph = document.paragraphs().get(1);

    assertNull(paragraph.firstLineIndentTwips());
    var result = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(check -> check.ruleId().equals("BODY-INDENT"))
        .findFirst().orElseThrow();
    assertEquals("INDENT_MISMATCH", result.reasonCode());
  }

  private ParsedDocument parseIndentEvidenceFixture(String attributes, boolean contents) throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    String ind = java.util.Arrays.stream(attributes.split(" "))
        .map(attribute -> "w:" + attribute).collect(java.util.stream.Collectors.joining(" "));
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:t>"
            + (contents ? "目    录" : "第一章 绪论")
            + "</w:t></w:r></w:p><w:p><w:pPr><w:ind " + ind + "/></w:pPr><w:r><w:t>"
            + (contents ? "第一章 绪论……1" : "验证缩进属性的有效值")
            + "</w:t></w:r></w:p><w:sectPr/></w:body></w:document>"));
    Path file = temp.resolve("indent-evidence.docx");
    pkg.save(file.toFile());
    return new Docx4jDocumentParser().parse(new DocxReader().read(file));
  }

  @Test
  void resolvesFirstLineIndentFromParagraphStyleWhenNoDirectIndentExists() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:pPr><w:pStyle w:val=\"Body\"/></w:pPr>"
            + "<w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:styleId=\"Body\"><w:name w:val=\"Body\"/>"
            + "<w:pPr><w:ind w:firstLine=\"480\"/></w:pPr></w:style></w:styles>"));
    Path file = temp.resolve("inherited-first-line-indent.docx");
    pkg.save(file.toFile());

    ParagraphModel paragraph = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(0);

    assertEquals(480, paragraph.firstLineIndentTwips());
  }

  @Test
  void resolvesCharacterFirstLineIndentFromParagraphStyleWhenNoDirectIndentExists() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:pPr><w:pStyle w:val=\"Body\"/></w:pPr>"
            + "<w:r><w:t>正文</w:t></w:r></w:p></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:styleId=\"Body\"><w:name w:val=\"Body\"/>"
            + "<w:pPr><w:ind w:firstLineChars=\"200\"/></w:pPr></w:style></w:styles>"));
    Path file = temp.resolve("inherited-first-line-indent-chars.docx");
    pkg.save(file.toFile());

    ParagraphModel paragraph = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(0);

    assertEquals(null, paragraph.firstLineIndentTwips());
    assertEquals(200, paragraph.firstLineChars());
  }

  @Test
  void resolvesFirstRowConditionalTableBordersIntoThreeLineEvidence() throws Exception {
    TableModel table = parseConditionalTableStyleFixture("", "");

    assertTrue(table.tableStyleBorderEvidenceResolved());
    assertTrue(table.topBorder());
    assertTrue(table.middleBorder());
    assertTrue(table.bottomBorder());
  }

  @Test
  void directCellNilBorderOverridesConditionalStyleBorder() throws Exception {
    TableModel table = parseConditionalTableStyleFixture("", "<w:tcPr><w:tcBorders>"
        + "<w:bottom w:val=\"nil\"/></w:tcBorders></w:tcPr>");

    assertTrue(table.tableStyleBorderEvidenceResolved());
    assertTrue(table.topBorder());
    assertFalse(table.middleBorder());
    assertTrue(table.bottomBorder());
  }

  @Test
  void directCellNilBorderSuppressesConditionalVerticalBorderEvidence() throws Exception {
    TableModel table = parseConditionalTableStyleFixture("",
        "<w:tcPr><w:tcBorders><w:right w:val=\"nil\"/></w:tcBorders></w:tcPr>", true);

    assertTrue(table.tableStyleBorderEvidenceResolved());
    assertFalse(table.verticalBorders());
  }

  @Test
  void disabledFirstRowLookDoesNotApplyFirstRowConditionalBorder() throws Exception {
    TableModel table = parseConditionalTableStyleFixture(
        "<w:tblLook w:val=\"0000\" w:firstRow=\"0\"/>", "");

    assertTrue(table.tableStyleBorderEvidenceResolved());
    assertTrue(table.topBorder());
    assertFalse(table.middleBorder());
    assertTrue(table.bottomBorder());
  }

  @Test
  void explicitCellConditionalStyleDoesNotBypassDisabledFirstRowLook() throws Exception {
    TableModel table = parseConditionalTableStyleFixture(
        "<w:tblLook w:val=\"0000\" w:firstRow=\"0\"/>",
        "<w:tcPr><w:cnfStyle w:firstRow=\"1\"/></w:tcPr>");

    assertTrue(table.tableStyleBorderEvidenceResolved());
    assertFalse(table.middleBorder());
  }

  @Test
  void gridBeforePreventsSecondGridColumnFromReceivingFirstColumnConditionalStyle() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:tbl><w:tblPr><w:tblStyle w:val=\"GridTable\"/>"
            + "<w:tblLook w:val=\"0000\" w:firstColumn=\"1\"/></w:tblPr>"
            + "<w:tblGrid><w:gridCol w:w=\"1000\"/><w:gridCol w:w=\"1000\"/></w:tblGrid>"
            + "<w:tr><w:trPr><w:gridBefore w:val=\"1\"/></w:trPr><w:tc><w:p/></w:tc></w:tr>"
            + "</w:tbl><w:sectPr/></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"table\" w:styleId=\"GridTable\">"
            + "<w:tblStylePr w:type=\"firstCol\"><w:tcPr><w:tcBorders>"
            + "<w:left w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
            + "</w:tcBorders></w:tcPr></w:tblStylePr></w:style></w:styles>"));
    Path file = temp.resolve("grid-before-first-column-conditional-style.docx");
    pkg.save(file.toFile());

    TableModel table = new Docx4jDocumentParser().parse(new DocxReader().read(file)).tables().get(0);

    assertTrue(table.tableStyleBorderEvidenceResolved());
    assertFalse(table.verticalBorders());
  }

  @Test
  void gridAfterPreventsFirstGridColumnFromReceivingLastColumnConditionalStyle() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:tbl><w:tblPr><w:tblStyle w:val=\"GridTable\"/>"
            + "<w:tblLook w:val=\"0000\" w:lastColumn=\"1\"/></w:tblPr>"
            + "<w:tr><w:trPr><w:gridAfter w:val=\"1\"/></w:trPr><w:tc><w:p/></w:tc></w:tr>"
            + "</w:tbl><w:sectPr/></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"table\" w:styleId=\"GridTable\">"
            + "<w:tblStylePr w:type=\"lastCol\"><w:tcPr><w:tcBorders>"
            + "<w:right w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
            + "</w:tcBorders></w:tcPr></w:tblStylePr></w:style></w:styles>"));
    Path file = temp.resolve("grid-after-last-column-conditional-style.docx");
    pkg.save(file.toFile());

    TableModel table = new Docx4jDocumentParser().parse(new DocxReader().read(file)).tables().get(0);

    assertTrue(table.tableStyleBorderEvidenceResolved());
    assertFalse(table.verticalBorders());
  }

  @Test
  void resolvesLastRowConditionalTopBorderAsThreeLineHeaderSeparator() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:tbl><w:tblPr><w:tblStyle w:val=\"LastRowTable\"/>"
            + "<w:tblLook w:val=\"0000\" w:lastRow=\"1\"/></w:tblPr>"
            + "<w:tr><w:tc><w:p/></w:tc></w:tr><w:tr><w:tc><w:p/></w:tc></w:tr>"
            + "</w:tbl><w:sectPr/></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"table\" w:styleId=\"LastRowTable\"><w:tblPr>"
            + "<w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
            + "<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
            + "</w:tblBorders></w:tblPr><w:tblStylePr w:type=\"lastRow\"><w:tcPr><w:tcBorders>"
            + "<w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
            + "</w:tcBorders></w:tcPr></w:tblStylePr></w:style></w:styles>"));
    Path file = temp.resolve("last-row-conditional-header-separator.docx");
    pkg.save(file.toFile());

    TableModel table = new Docx4jDocumentParser().parse(new DocxReader().read(file)).tables().get(0);

    assertTrue(table.tableStyleBorderEvidenceResolved());
    assertTrue(table.topBorder());
    assertTrue(table.middleBorder());
    assertTrue(table.bottomBorder());
  }

  @Test
  void resolvesConditionalBordersWhenRowsAreWrappedByCustomXml() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:tbl><w:tblPr><w:tblStyle w:val=\"WrappedRows\"/>"
            + "<w:tblLook w:val=\"0000\" w:firstRow=\"1\"/></w:tblPr>"
            + "<w:customXml w:uri=\"urn:example\" w:element=\"rows\">"
            + "<w:tr><w:tc><w:p/></w:tc></w:tr><w:tr><w:tc><w:p/></w:tc></w:tr>"
            + "</w:customXml></w:tbl><w:sectPr/></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"table\" w:styleId=\"WrappedRows\">"
            + "<w:tblStylePr w:type=\"firstRow\"><w:tcPr><w:tcBorders>"
            + "<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
            + "</w:tcBorders></w:tcPr></w:tblStylePr></w:style></w:styles>"));
    Path file = temp.resolve("conditional-borders-custom-xml-rows.docx");
    pkg.save(file.toFile());

    TableModel table = new Docx4jDocumentParser().parse(new DocxReader().read(file)).tables().get(0);

    assertEquals(2, table.rows());
    assertTrue(table.tableStyleBorderEvidenceResolved());
    assertTrue(table.middleBorder());
  }

  @Test
  void resolvesConditionalBordersInheritedFromBasedOnTableStyle() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:tbl><w:tblPr><w:tblStyle w:val=\"DerivedTable\"/>"
            + "</w:tblPr><w:tr><w:tc><w:p/></w:tc></w:tr>"
            + "<w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl><w:sectPr/></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"table\" w:styleId=\"BaseTable\"><w:tblPr>"
            + "<w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
            + "<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
            + "</w:tblBorders></w:tblPr><w:tblStylePr w:type=\"firstRow\"><w:tcPr><w:tcBorders>"
            + "<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
            + "</w:tcBorders></w:tcPr></w:tblStylePr></w:style>"
            + "<w:style w:type=\"table\" w:styleId=\"DerivedTable\"><w:basedOn w:val=\"BaseTable\"/>"
            + "</w:style></w:styles>"));
    Path file = temp.resolve("based-on-conditional-table-borders.docx");
    pkg.save(file.toFile());

    TableModel table = new Docx4jDocumentParser().parse(new DocxReader().read(file)).tables().get(0);

    assertTrue(table.tableStyleBorderEvidenceResolved());
    assertTrue(table.topBorder());
    assertTrue(table.middleBorder());
    assertTrue(table.bottomBorder());
  }

  @Test
  void resolvesHorizontalAndVerticalBandConditionalBordersAtTheirGridPositions() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:tbl><w:tblPr><w:tblStyle w:val=\"BandedTable\"/>"
            + "<w:tblLook w:val=\"0000\" w:firstRow=\"0\" w:lastRow=\"0\""
            + " w:firstColumn=\"0\" w:lastColumn=\"0\" w:noHBand=\"0\" w:noVBand=\"0\"/>"
            + "</w:tblPr><w:tblGrid><w:gridCol w:w=\"1000\"/><w:gridCol w:w=\"1000\"/></w:tblGrid>"
            + "<w:tr><w:tc><w:p/></w:tc><w:tc><w:p/></w:tc></w:tr>"
            + "<w:tr><w:tc><w:p/></w:tc><w:tc><w:p/></w:tc></w:tr>"
            + "</w:tbl><w:sectPr/></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"table\" w:styleId=\"BandedTable\"><w:tblPr>"
            + "<w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
            + "<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
            + "</w:tblBorders></w:tblPr>"
            + "<w:tblStylePr w:type=\"band2Horz\"><w:tcPr><w:tcBorders>"
            + "<w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
            + "</w:tcBorders></w:tcPr></w:tblStylePr>"
            + "<w:tblStylePr w:type=\"band1Vert\"><w:tcPr><w:tcBorders>"
            + "<w:left w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
            + "</w:tcBorders></w:tcPr></w:tblStylePr></w:style></w:styles>"));
    Path file = temp.resolve("horizontal-vertical-banded-table-borders.docx");
    pkg.save(file.toFile());

    TableModel table = new Docx4jDocumentParser().parse(new DocxReader().read(file)).tables().get(0);

    assertTrue(table.tableStyleBorderEvidenceResolved());
    assertTrue(table.topBorder());
    assertTrue(table.middleBorder());
    assertTrue(table.bottomBorder());
    assertTrue(table.verticalBorders());
  }

  private TableModel parseConditionalTableStyleFixture(String look, String firstCellProperties)
      throws Exception {
    return parseConditionalTableStyleFixture(look, firstCellProperties, false);
  }

  private TableModel parseConditionalTableStyleFixture(String look, String firstCellProperties,
      boolean firstRowVerticalBorder) throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:tbl><w:tblPr><w:tblStyle w:val=\"ThesisTable\"/>"
            + look + "</w:tblPr><w:tr><w:tc>" + firstCellProperties + "<w:p/></w:tc></w:tr>"
            + "<w:tr><w:tc><w:p/></w:tc></w:tr></w:tbl><w:sectPr/></w:body></w:document>"));
    String conditionalBorders = "<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
        + (firstRowVerticalBorder
            ? "<w:right w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>" : "");
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"table\" w:styleId=\"ThesisTable\"><w:tblPr>"
            + "<w:tblBorders><w:top w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
            + "<w:bottom w:val=\"single\" w:sz=\"4\" w:color=\"auto\" w:space=\"0\"/>"
            + "</w:tblBorders></w:tblPr><w:tblStylePr w:type=\"firstRow\"><w:tcPr><w:tcBorders>"
            + conditionalBorders + "</w:tcBorders></w:tcPr></w:tblStylePr></w:style></w:styles>"));
    Path file = temp.resolve("first-row-conditional-table-style.docx");
    pkg.save(file.toFile());

    return new Docx4jDocumentParser().parse(new DocxReader().read(file)).tables().get(0);
  }

  @Test
  void rejectsConflictingFirstLineAndHangingIndentAttributes() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:t>第一章 绪论</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:ind w:firstLine=\"480\" w:hanging=\"480\"/></w:pPr>"
            + "<w:r><w:t>存在冲突缩进属性的正文</w:t></w:r></w:p>"
            + "<w:sectPr/></w:body></w:document>"));
    Path file = temp.resolve("conflicting-first-line-and-hanging-indent.docx");
    pkg.save(file.toFile());

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));

    assertTrue(new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .anyMatch(result -> result.ruleId().equals("BODY-INDENT")
            && result.source().elementPath().equals(document.paragraphs().get(1).source().elementPath())
            && result.judgement() == cn.luwneshacha.thesis.rules.Judgement.FAIL));
  }

  @Test
  void rejectsConflictingCharacterFirstLineAndHangingIndentAttributes() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:t>第一章 绪论</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:ind w:firstLineChars=\"200\" w:hangingChars=\"200\"/></w:pPr>"
            + "<w:r><w:t>存在冲突字符缩进属性的正文</w:t></w:r></w:p>"
            + "<w:sectPr/></w:body></w:document>"));
    Path file = temp.resolve("conflicting-character-first-line-and-hanging-indent.docx");
    pkg.save(file.toFile());

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));

    assertTrue(new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .anyMatch(result -> result.ruleId().equals("BODY-INDENT")
            && result.source().elementPath().equals(document.paragraphs().get(1).source().elementPath())
            && result.judgement() == cn.luwneshacha.thesis.rules.Judgement.FAIL));
  }

  @Test
  void resolvesEffectiveLeftIndentInCharacterUnits() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:pPr><w:pStyle w:val=\"Normal\"/></w:pPr>"
            + "<w:r><w:t>第一章 引言</w:t></w:r></w:p></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>"
            + "<w:pPr><w:ind w:leftChars=\"200\"/></w:pPr></w:style></w:styles>"));
    Path file = temp.resolve("left-indent-chars.docx");
    pkg.save(file.toFile());

    ParagraphModel paragraph = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(0);

    assertEquals(200, paragraph.leftIndentChars());
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
  void doesNotApplyBodyFormattingToHeadingWithCustomStyleId() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:t>第一章 引言</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:pStyle w:val=\"2\"/></w:pPr>"
            + "<w:r><w:rPr><w:rFonts w:eastAsia=\"黑体\"/><w:sz w:val=\"32\"/><w:b/></w:rPr>"
            + "<w:t>系统需求分析</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:pStyle w:val=\"Normal\"/></w:pPr>"
            + "<w:r><w:rPr><w:rFonts w:eastAsia=\"宋体\"/><w:sz w:val=\"24\"/></w:rPr>"
            + "<w:t>普通正文内容</w:t></w:r></w:p>"
            + "</w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:style w:type=\"paragraph\" w:styleId=\"2\">"
            + "<w:name w:val=\"heading 1\"/><w:pPr><w:outlineLvl w:val=\"0\"/><w:jc w:val=\"center\"/></w:pPr>"
            + "</w:style><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
            + "<w:name w:val=\"Normal\"/><w:pPr><w:outlineLvl w:val=\"0\"/></w:pPr>"
            + "</w:style></w:styles>"));
    Path file = temp.resolve("custom-heading-style-id.docx");
    pkg.save(file.toFile());

    ParsedDocument document = new Docx4jDocumentParser().parse(new DocxReader().read(file));

    assertEquals(0, document.paragraphs().get(1).outlineLevel());
    assertEquals("2", document.paragraphs().get(1).paragraphStyleId());
    assertEquals("heading 1", document.paragraphs().get(1).paragraphStyleName());
    assertEquals(0, document.paragraphs().get(2).outlineLevel());
    assertEquals("Normal", document.paragraphs().get(2).paragraphStyleName());
    var findings = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1());
    assertTrue(findings.stream().noneMatch(result -> result.source().equals(document.paragraphs().get(1).source())
        && result.ruleId().startsWith("BODY-")), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.source().equals(document.paragraphs().get(1).source())
        && result.ruleId().equals("HEADING-FONT")), findings.toString());
    assertTrue(findings.stream().anyMatch(result -> result.source().equals(document.paragraphs().get(2).source())
        && result.ruleId().equals("BODY-LINE")), findings.toString());
    assertTrue(findings.stream().noneMatch(result -> result.source().equals(document.paragraphs().get(2).source())
        && result.ruleId().startsWith("HEADING-")), findings.toString());
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
  void normalizesDirectionalWordAlignmentValues() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:pPr><w:jc w:val=\"end\"/></w:pPr><w:r><w:t>续表4-1</w:t></w:r></w:p>"
            + "</w:body></w:document>"));
    Path file = temp.resolve("directional-alignment.docx");
    pkg.save(file.toFile());

    ParagraphModel paragraph = new Docx4jDocumentParser().parse(new DocxReader().read(file)).paragraphs().get(0);

    assertEquals("right", paragraph.alignment());
  }

  @Test
  void rejectsInvalidRawParagraphAlignmentInsteadOfDefaultingToLeft() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    Path file = temp.resolve("invalid-jc-" + java.util.UUID.randomUUID() + ".docx");
    pkg.save(file.toFile());
    String document = "<w:document " + NS + "><w:body>"
        + "<w:p><w:pPr><w:jc w:val=\"CENTER\"/></w:pPr><w:r><w:t>invalid</w:t></w:r></w:p>"
        + "<w:sectPr/></w:body></w:document>";
    Path patched = replaceZipPart(file, "word/document.xml", document, null);

    IllegalArgumentException error;
    try {
      new Docx4jDocumentParser().parse(new DocxReader().read(patched));
      throw new AssertionError("invalid w:jc was accepted");
    } catch (IllegalArgumentException expected) {
      error = expected;
    }
    assertTrue(error.getMessage().contains("unable to parse DOCX"));
    assertTrue(error.getCause().getMessage().contains("invalid w:jc"));
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

  @Test
  void chapterBreakEvidenceIncludesAutomaticHeadingOneChapters() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:pPr><w:outlineLvl w:val=\"0\"/><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>"
            + "<w:r><w:t>绪论</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:outlineLvl w:val=\"0\"/><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr></w:pPr>"
            + "<w:r><w:t>系统设计</w:t></w:r></w:p>"
            + "</w:body></w:document>"));
    var numberingPart = new org.docx4j.openpackaging.parts.WordprocessingML.NumberingDefinitionsPart();
    numberingPart.setJaxbElement((org.docx4j.wml.Numbering) XmlUtils.unmarshalString(
        "<w:numbering " + NS + "><w:abstractNum w:abstractNumId=\"0\"><w:lvl w:ilvl=\"0\">"
            + "<w:start w:val=\"1\"/><w:numFmt w:val=\"decimal\"/><w:lvlText w:val=\"第%1章\"/>"
            + "<w:suff w:val=\"space\"/></w:lvl></w:abstractNum><w:num w:numId=\"1\">"
            + "<w:abstractNumId w:val=\"0\"/></w:num></w:numbering>"));
    pkg.getMainDocumentPart().addTargetPart(numberingPart);
    Path file = temp.resolve("automatic-chapter-breaks.docx");
    pkg.save(file.toFile());

    List<HeadingBreakModel> breaks = new Docx4jDocumentParser().parse(new DocxReader().read(file)).chapterBreaks();

    assertEquals(List.of("第1章 绪论", "第2章 系统设计"), breaks.stream().map(HeadingBreakModel::text).toList());
  }

  @Test
  void pageBreakEvidenceIncludesAppendixTitlesAndAutomaticNumberedChapters() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:t>第一章 绪论</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:pageBreakBefore/></w:pPr><w:r><w:t>附    录</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t>附录A 补充材料</w:t></w:r></w:p>"
            + "</w:body></w:document>"));
    Path file = temp.resolve("appendix-breaks.docx");
    pkg.save(file.toFile());

    List<HeadingBreakModel> breaks = new Docx4jDocumentParser().parse(new DocxReader().read(file)).chapterBreaks();

    assertEquals(List.of("第一章 绪论", "附    录"), breaks.stream().map(HeadingBreakModel::text).toList());
    assertEquals(List.of(false, true), breaks.stream().map(HeadingBreakModel::explicitBreak).toList());
  }

  @Test
  void pageBreakAfterAHeadingDoesNotProveThatTheHeadingStartsOnANewPage() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:t>第一章 绪论</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t>正文内容</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t>第二章 设计</w:t><w:br w:type=\"page\"/></w:r></w:p>"
            + "</w:body></w:document>"));
    Path file = temp.resolve("heading-trailing-break.docx");
    pkg.save(file.toFile());

    List<HeadingBreakModel> breaks = new Docx4jDocumentParser().parse(new DocxReader().read(file)).chapterBreaks();

    assertFalse(breaks.get(1).explicitBreak());
  }

  @Test
  void excludesGeneratedContentsFieldEntriesFromChapterBreakEvidence() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body>"
            + "<w:p><w:r><w:t>目录</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:pStyle w:val=\"TOC1\"/></w:pPr>"
            + "<w:r><w:t>第二章  研究方法</w:t></w:r>"
            + "<w:r><w:instrText xml:space=\"preserve\"> PAGEREF _Toc123 \\\\h </w:instrText></w:r>"
            + "<w:r><w:tab/></w:r><w:r><w:t>2</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t>第一章 绪论</w:t></w:r></w:p>"
            + "<w:p><w:r><w:t>第二章 研究方法</w:t></w:r></w:p>"
            + "</w:body></w:document>"));
    Path file = temp.resolve("generated-toc-chapters.docx");
    pkg.save(file.toFile());

    ParsedDocument parsed = new Docx4jDocumentParser().parse(new DocxReader().read(file));
    List<HeadingBreakModel> breaks = parsed.chapterBreaks();

    assertEquals(List.of("第一章 绪论", "第二章 研究方法"), breaks.stream().map(HeadingBreakModel::text).toList());
    assertTrue(parsed.paragraphs().get(1).generatedTocField());
    assertEquals("TOC1", parsed.paragraphs().get(1).paragraphStyleId());
  }

  @Test
  void checksComplexScriptSizeFromSzCsInsteadOfTheRegularSize() throws Exception {
    ParsedDocument document = parseComplexScriptSizeFixture(22, "العربية");
    var result = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-SIZE"))
        .findFirst().orElseThrow();

    assertEquals(cn.luwneshacha.thesis.rules.Judgement.FAIL, result.judgement());
    assertEquals("SIZE_MISMATCH", result.reasonCode());
    assertTrue(result.actual().contains("szCs=22"));
    assertEquals(22, document.paragraphs().get(1).runs().get(0).complexFontSizeHalfPoints());
  }

  @Test
  void acceptsComplexScriptWhenBothApplicableSizeSlotsMatch() throws Exception {
    ParsedDocument document = parseComplexScriptSizeFixture(24, "العربية");

    var result = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-SIZE")).findFirst().orElseThrow();

    assertEquals(cn.luwneshacha.thesis.rules.Judgement.PASS, result.judgement());
  }

  @Test
  void ignoresComplexSizeSlotForLatinOnlyRuns() throws Exception {
    ParsedDocument document = parseComplexScriptSizeFixture(22, "English");

    var result = new cn.luwneshacha.thesis.rules.RuleEngine()
        .check(document, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()).stream()
        .filter(item -> item.ruleId().equals("BODY-SIZE")).findFirst().orElseThrow();

    assertEquals(cn.luwneshacha.thesis.rules.Judgement.PASS, result.judgement());
  }

  private ParsedDocument parseComplexScriptSizeFixture(int complexSize, String text) throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:r><w:t>第一章 绪论</w:t></w:r></w:p>"
            + "<w:p><w:pPr><w:ind w:firstLineChars=\"200\"/></w:pPr>"
            + "<w:r><w:rPr><w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\" "
            + "w:eastAsia=\"宋体\" w:cs=\"Times New Roman\"/><w:sz w:val=\"24\"/>"
            + "<w:szCs w:val=\"" + complexSize + "\"/></w:rPr><w:t>" + text + "</w:t></w:r></w:p>"
            + "<w:sectPr/></w:body></w:document>"));
    Path file = temp.resolve("complex-script-size-" + complexSize + "-" + text + ".docx");
    pkg.save(file.toFile());
    return new Docx4jDocumentParser().parse(new DocxReader().read(file));
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

  private RawDocx addHeader(RawDocx source, String headerPart, String relationshipId, String headerXml) throws Exception {
    return addHeader(source, headerPart, relationshipId, "default", headerXml);
  }

  private RawDocx addHeader(RawDocx source, String headerPart, String relationshipId,
      String type, String headerXml) throws Exception {
    return addHeader(source, headerPart, relationshipId, type, headerXml, false);
  }

  private RawDocx addHeader(RawDocx source, String headerPart, String relationshipId,
      String type, String headerXml, boolean firstSection) throws Exception {
    var parts = new LinkedHashMap<>(source.parts());
    parts.put(headerPart, new RawPart(headerPart, "test",
        headerXml.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    String document = new String(parts.get("word/document.xml").bytes(), java.nio.charset.StandardCharsets.UTF_8);
    if (!document.contains("xmlns:r="))
      document = document.replace("<w:document ", "<w:document xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" ");
    int sectionEnd = firstSection ? document.indexOf("</w:sectPr>") : document.lastIndexOf("</w:sectPr>");
    if (sectionEnd < 0) throw new IllegalStateException("fixture has no final section properties");
    document = document.substring(0, sectionEnd)
        + "<w:headerReference w:type=\"" + type + "\" r:id=\"" + relationshipId + "\"/>"
        + document.substring(sectionEnd);
    parts.put("word/document.xml", new RawPart("word/document.xml", "test",
        document.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));

    String relsName = "word/_rels/document.xml.rels";
    String relationships = parts.containsKey(relsName)
        ? new String(parts.get(relsName).bytes(), java.nio.charset.StandardCharsets.UTF_8)
        : "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"></Relationships>";
    String target = headerPart.substring("word/".length());
    relationships = relationships.replace("</Relationships>", "<Relationship Id=\"" + relationshipId
        + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\" Target=\""
        + target + "\"/></Relationships>");
    parts.put(relsName, new RawPart(relsName, "test",
        relationships.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    return new RawDocx(source.sourceSha256(), source.sourceBytes(), parts, source.entryCount());
  }
}
