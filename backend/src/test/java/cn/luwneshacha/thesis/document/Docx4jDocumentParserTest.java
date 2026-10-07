package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
