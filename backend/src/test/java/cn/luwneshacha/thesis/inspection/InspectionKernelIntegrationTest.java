package cn.luwneshacha.thesis.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.inspection.InspectionException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.nio.file.Files;
import java.io.OutputStream;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Document;
import org.docx4j.wml.Styles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cn.luwneshacha.thesis.report.JsonReportWriter;

class InspectionKernelIntegrationTest {
  private static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";
  @TempDir Path temp;

  @Test
  void inspectsDocxAndPublishesTraceableJsonReport() throws Exception {
    Path source = fixture();

    InspectionReport report = new InspectionKernel().inspect(source, cn.luwneshacha.thesis.rules.RuleSet.schoolV1());
    String json = new JsonReportWriter().write(report);

    assertEquals("school-v1", report.ruleSetId());
    assertEquals(64, report.sourceSha256().length());
    assertTrue(report.results().stream().anyMatch(r -> r.ruleId().equals("PAGE-01")));
    assertTrue(json.contains("word/document.xml"));
    assertTrue(json.contains("1474"));
  }

  @Test
  void preservesTheParserCauseForServerDiagnostics() throws Exception {
    Path source = temp.resolve("malformed-package.docx");
    try (OutputStream file = Files.newOutputStream(source); ZipOutputStream zip = new ZipOutputStream(file)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write(("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
          + "<Default Extension=\"xml\" ContentType=\"application/xml\"/></Types>")
          .getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("word/document.xml"));
      zip.write("<broken".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }

    InspectionException error = assertThrows(InspectionException.class,
        () -> new InspectionKernel().inspect(source, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()));

    assertEquals("INVALID_DOCX", error.code());
    assertNotNull(error.getCause());
  }

  private Path fixture() throws Exception {
    var pkg = WordprocessingMLPackage.createPackage();
    pkg.getMainDocumentPart().setContents((Document) XmlUtils.unmarshalString(
        "<w:document " + NS + "><w:body><w:p><w:pPr><w:ind w:firstLineChars=\"200\"/></w:pPr><w:r><w:t>正文A</w:t></w:r></w:p>"
            + "<w:sectPr><w:pgMar w:top=\"1474\" w:right=\"1474\" w:bottom=\"1474\" w:left=\"1474\"/></w:sectPr></w:body></w:document>"));
    pkg.getMainDocumentPart().getStyleDefinitionsPart().setContents((Styles) XmlUtils.unmarshalString(
        "<w:styles " + NS + "><w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val=\"24\"/></w:rPr></w:rPrDefault></w:docDefaults><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:rPr><w:rFonts w:ascii=\"Times New Roman\" w:eastAsia=\"宋体\"/><w:sz w:val=\"24\"/></w:rPr></w:style></w:styles>"));
    Path file = temp.resolve("integration.docx");
    pkg.save(file.toFile());
    return file;
  }
}
