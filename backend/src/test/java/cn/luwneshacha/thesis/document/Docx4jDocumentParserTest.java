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
  void recognizesPageFieldsWithSwitchesAndSimpleFieldSyntax() throws Exception {
    RawDocx valid = new DocxReader().read(fixture());
    var parts = new LinkedHashMap<>(valid.parts());
    String footer = "<w:ftr " + NS + "><w:p><w:r><w:instrText xml:space=\"preserve\"> PA</w:instrText></w:r>"
        + "<w:r><w:instrText xml:space=\"preserve\">GE \\* MERGEFORMAT</w:instrText></w:r></w:p>"
        + "<w:p><w:fldSimple w:instr=\" PAGE \\* MERGEFORMAT \"><w:r><w:t>1</w:t></w:r></w:fldSimple></w:p></w:ftr>";
    parts.put("word/footer1.xml", new RawPart("word/footer1.xml", "test", footer.getBytes(java.nio.charset.StandardCharsets.UTF_8), true));
    RawDocx withFooter = new RawDocx(valid.sourceSha256(), valid.sourceBytes(), parts, valid.entryCount());

    ParsedDocument document = new Docx4jDocumentParser().parse(withFooter);

    assertEquals(1, document.pageNumbers().size());
    assertTrue(document.pageNumbers().get(0).pageField());
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
}
