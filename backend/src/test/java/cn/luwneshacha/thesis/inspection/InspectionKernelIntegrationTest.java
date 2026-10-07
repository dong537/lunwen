package cn.luwneshacha.thesis.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
