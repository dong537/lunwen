package cn.luwneshacha.thesis.inspection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.inspection.InspectionException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import java.nio.file.Files;
import java.io.OutputStream;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.docx4j.XmlUtils;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.wml.Document;
import org.docx4j.wml.Styles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cn.luwneshacha.thesis.report.JsonReportWriter;
import cn.luwneshacha.thesis.rules.RuleRun;
import cn.luwneshacha.thesis.rendering.RenderedLayoutEvidence;

class InspectionKernelIntegrationTest {
  private static final String NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";
  @TempDir Path temp;

  @Test
  void reportsLayoutProvenanceWithoutTreatingItAsRulePassEvidence() throws Exception {
    Path source = fixture();
    String sha = new cn.luwneshacha.thesis.document.DocxReader().read(source).sourceSha256();
    var provenance = new RenderedLayoutEvidence.Provenance("available", sha, "pdf-sha",
        "LibreOffice", "LibreOffice 26.8", "2026-11-18T00:00:00Z", null, 2);
    var layout = new RenderedLayoutEvidence(provenance, java.util.List.of());

    InspectionReport report = new InspectionKernel().inspect(source,
        cn.luwneshacha.thesis.rules.RuleSet.schoolV1(), layout);

    assertEquals(provenance, report.layoutEvidence());
    assertTrue(new JsonReportWriter().write(report).contains("LibreOffice 26.8"));
    assertTrue(report.ruleExecution().ruleRuns().stream()
        .anyMatch(run -> run.ruleId().equals("EN-03-ALIGNMENT") && run.unknownCount() > 0)
        || report.results().stream().noneMatch(row -> row.ruleId().equals("EN-03-ALIGNMENT")));
  }

  @Test
  void inspectsDocxAndPublishesTraceableJsonReport() throws Exception {
    Path source = fixture();

    InspectionReport report = new InspectionKernel().inspect(source, cn.luwneshacha.thesis.rules.RuleSet.schoolV1());
    String json = new JsonReportWriter().write(report);
    Files.writeString(Path.of("target", "coverage-contract-sample.json"), json, StandardCharsets.UTF_8);

    assertEquals("school-v1", report.ruleSetId());
    assertEquals(64, report.sourceSha256().length());
    assertTrue(report.results().stream().anyMatch(r -> r.ruleId().equals("PAGE-01")));
    assertTrue(json.contains("word/document.xml"));
    assertTrue(json.contains("1474"));
    assertNotNull(report.ruleExecution());
    assertEquals(2, report.ruleExecution().schemaVersion());
    assertTrue(json.contains("\"ownerPairsSha256\""));
    assertTrue(json.contains("\"ruleRuns\""));
    assertTrue(json.contains("\"NUMBERING-01\""));
    assertEquals(RuleCatalog.entries().stream().map(RuleCatalog.Entry::ruleId).toList(),
        report.ruleExecution().catalogRuleIds());
    assertEquals(report.ruleExecution().catalogRuleIds(), report.ruleExecution().evaluatedRuleIds());
    assertTrue(report.ruleExecution().completedModuleIds().contains("BodyFormatRuleChecker"));
    assertTrue(report.ruleExecution().ruleRuns().contains(
        new RuleRun("NUMBERING-01", "AutomaticNumberingRuleModule", 1, 1, 0, null)));
    assertTrue(report.ruleExecution().ruleRuns().contains(
        new RuleRun("PAGE-01", "SectionLayoutRuleChecker", 4, 4, 0, null)));
    assertTrue(json.contains("\"ruleExecution\""));
    assertTrue(json.contains("\"BODY-SCOPE\""));
    var bodyScope = report.ruleCoverage().stream()
        .filter(row -> row.ruleId().equals("BODY-SCOPE")).findFirst().orElseThrow();
    assertEquals(0, bodyScope.total());
    assertEquals("无结果证据", bodyScope.executionStatus());
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

  @Test
  void doesNotProduceHardFindingsWhenStylesHaveUnverifiedAlternatives() throws Exception {
    Path source = replaceZipPart(fixture(), "word/styles.xml", xml -> xml.replace("</w:styles>",
        "<mc:AlternateContent xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\">"
            + "<mc:Choice Requires=\"w\"><w:style w:type=\"paragraph\" w:styleId=\"OtherChoice\"/></mc:Choice>"
            + "<mc:Fallback><w:style w:type=\"paragraph\" w:styleId=\"OtherFallback\"/></mc:Fallback>"
            + "</mc:AlternateContent></w:styles>"));

    InspectionReport report = new InspectionKernel().inspect(source,
        cn.luwneshacha.thesis.rules.RuleSet.schoolV1());

    assertTrue(report.results().isEmpty(), "unverified style view cannot produce school-rule errors");
    assertTrue(report.ruleExecution().completedModuleIds().isEmpty());
    assertTrue(report.ruleExecution().ruleRuns().isEmpty());
    assertTrue(report.uncheckedRegions().stream().anyMatch(region ->
        region.partName().equals("word/styles.xml") && region.kind() == CoverageKind.NOT_INSPECTED));
  }

  @Test
  void doesNotJudgeAnUnknownBodyImageBranchAsACompletePaper() throws Exception {
    Path source = replaceZipPart(fixture(), "word/document.xml", xml -> xml.replaceFirst(
        "</w:p>",
        "<mc:AlternateContent xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" "
            + "xmlns:future=\"urn:example:future-office\">"
            + "<mc:Choice Requires=\"future\"><w:r><w:drawing/></w:r></mc:Choice>"
            + "<mc:Fallback><w:r><w:drawing/></w:r></mc:Fallback></mc:AlternateContent></w:p>"));

    InspectionReport report = new InspectionKernel().inspect(source,
        cn.luwneshacha.thesis.rules.RuleSet.schoolV1());

    assertTrue(report.results().isEmpty());
    assertTrue(report.ruleExecution().completedModuleIds().isEmpty());
    assertTrue(report.ruleExecution().ruleRuns().isEmpty());
    assertTrue(report.uncheckedRegions().stream().anyMatch(region ->
        region.partName().equals("word/document.xml")
            && region.region().contains("兼容内容分支")
            && region.kind() == CoverageKind.NOT_INSPECTED), report.uncheckedRegions().toString());
  }

  @Test
  void groupedGraphicCompatibilityDoesNotStopUnrelatedSchoolRules() throws Exception {
    String group = """
        <w:r><mc:AlternateContent
            xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
            xmlns:wpg="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup"
            xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
            xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
            xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
            xmlns:v="urn:schemas-microsoft-com:vml">
          <mc:Choice Requires="wpg"><w:drawing><wp:anchor><a:graphic><a:graphicData>
            <wpg:wgp><wpg:cNvGrpSpPr/><wpg:grpSpPr/>%s</wpg:wgp>
          </a:graphicData></a:graphic></wp:anchor></w:drawing></mc:Choice>
          <mc:Fallback><w:pict><v:group>%s</v:group></w:pict></mc:Fallback>
        </mc:AlternateContent></w:r>
        """.formatted("<wps:wsp><wps:spPr/></wps:wsp>".repeat(6), "<v:line/>".repeat(6));
    Path source = replaceZipPart(fixture(), "word/document.xml", xml ->
        xml.replaceFirst("</w:p>", group + "</w:p>"));

    InspectionReport report = new InspectionKernel().inspect(source,
        cn.luwneshacha.thesis.rules.RuleSet.schoolV1());

    assertTrue(report.ruleExecution().ruleRuns().size() > 0, "ordinary rules must execute");
    assertTrue(report.uncheckedRegions().stream().anyMatch(region ->
        region.region().startsWith("纯图形兼容内容分支")
            && region.kind() == CoverageKind.NOT_INSPECTED));
    assertTrue(report.uncheckedRegions().stream().noneMatch(region ->
        region.region().startsWith("兼容内容分支")));
  }

  @Test
  void reportsUnmappableParagraphBranchWithSpecificPublicError() throws Exception {
    Path source = replaceZipPart(fixture(), "word/document.xml", ignored ->
        "<w:document " + NS
            + " xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\"><w:body>"
            + "<mc:AlternateContent><mc:Choice Requires=\"w\"><w:p><w:r><w:t>A</w:t></w:r></w:p>"
            + "</mc:Choice><mc:Fallback><w:p><w:r><w:t>C</w:t></w:r></w:p>"
            + "</mc:Fallback></mc:AlternateContent><w:sectPr/></w:body></w:document>");

    InspectionException error = assertThrows(InspectionException.class, () ->
        new InspectionKernel().inspect(source, cn.luwneshacha.thesis.rules.RuleSet.schoolV1()));

    assertEquals("COMPATIBILITY_CONTENT_UNSUPPORTED", error.code(), error.getCause().toString());
    assertTrue(error.getMessage().contains("另存"));
    assertNotNull(error.getCause());
  }

  private Path replaceZipPart(Path source, String partName,
      UnaryOperator<String> replacement) throws Exception {
    Path target = temp.resolve("alternate-" + UUID.randomUUID() + ".docx");
    try (var input = new ZipFile(source.toFile());
        var output = new ZipOutputStream(Files.newOutputStream(target))) {
      var entries = input.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        output.putNextEntry(new ZipEntry(entry.getName()));
        byte[] data = input.getInputStream(entry).readAllBytes();
        if (entry.getName().equals(partName)) {
          String original = new String(data, StandardCharsets.UTF_8);
          String updated = replacement.apply(original);
          assertTrue(!original.equals(updated), "fixture did not modify " + partName);
          data = updated.getBytes(StandardCharsets.UTF_8);
        }
        output.write(data);
        output.closeEntry();
      }
    }
    return target;
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
