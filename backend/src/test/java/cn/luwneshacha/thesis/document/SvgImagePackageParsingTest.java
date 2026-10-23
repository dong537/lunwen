package cn.luwneshacha.thesis.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.inspection.InspectionException;
import cn.luwneshacha.thesis.reporting.AnnotationFindings;
import cn.luwneshacha.thesis.reporting.AnnotationStatus;
import cn.luwneshacha.thesis.reporting.CommentWriter;
import cn.luwneshacha.thesis.rules.Judgement;
import cn.luwneshacha.thesis.rules.RuleEngine;
import cn.luwneshacha.thesis.rules.RuleSet;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SvgImagePackageParsingTest {
  private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
  private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  private static final String RELS = "http://schemas.openxmlformats.org/package/2006/relationships";
  private static final String SVG_DOCTYPE = "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" "
      + "\"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">";
  private static final byte[] PNG = Base64.getDecoder().decode(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j7l8AAAAASUVORK5CYII=");
  @TempDir Path temp;

  @Test
  void standardSvgDoctypePreservesImageCaptionChecksAndAllMediaOnAnnotation() throws Exception {
    Path input = fixture(svg(SVG_DOCTYPE), "image/svg+xml", R + "/image", "image.svg");
    var raw = new DocxReader().read(input);
    var parsed = new Docx4jDocumentParser().parse(raw);

    assertEquals(List.of("图3-1 系统结构图", "图3-2 普通图片"),
        parsed.images().stream().map(ImageModel::captionText).toList());
    assertEquals(List.of(914400L, 1828800L), parsed.images().stream().map(ImageModel::widthEmu).toList());
    assertEquals(List.of(457200L, 914400L), parsed.images().stream().map(ImageModel::heightEmu).toList());
    assertTrue(parsed.images().stream().allMatch(ImageModel::inline));
    var findings = new RuleEngine().check(parsed, RuleSet.schoolV1());
    assertTrue(findings.stream().anyMatch(f -> f.ruleId().equals("CAPTION-SIZE")
        && f.judgement() == Judgement.FAIL && f.actual().equals("24")));
    assertTrue(findings.stream().anyMatch(f -> f.ruleId().equals("NUMBERING-01")
        && f.judgement() == Judgement.FAIL));
    var selected = AnnotationFindings.select(findings.stream()
        .filter(f -> f.ruleId().startsWith("CAPTION-") || f.ruleId().equals("NUMBERING-01"))
        .toList()).representatives();
    assertTrue(!selected.isEmpty());
    Path output = temp.resolve("annotated.docx");
    var report = new CommentWriter().writeCopy(input, output, raw.sourceSha256(), selected);
    assertTrue(report.outputWritten());
    assertTrue(report.items().stream().allMatch(i -> i.status() == AnnotationStatus.WRITTEN));
    var outputRaw = new DocxReader().read(output);
    assertMediaUnchanged(raw, outputRaw);
    var reread = new Docx4jDocumentParser().parse(outputRaw);
    assertEquals(parsed.images(), reread.images());
    assertEquals(parsed.automaticNumbering(), reread.automaticNumbering());
    assertEquals(text(parsed), text(reread));
    assertEquals(raw.sourceSha256(), new DocxReader().read(input).sourceSha256());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void svgExternalDtdAndEntityRemainOpaqueWithoutNetworkAccess(boolean externalEntity) throws Exception {
    var requests = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/external", exchange -> {
      requests.incrementAndGet();
      byte[] response = "external content must not be read".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      try (var body = exchange.getResponseBody()) { body.write(response); }
    });
    server.start();
    try {
      String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/external";
      String payload = externalEntity
          ? "<!DOCTYPE svg [<!ENTITY external SYSTEM \"" + endpoint + "\">]>"
              + "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>&external;</text></svg>"
          : svg("<!DOCTYPE svg SYSTEM \"" + endpoint + "\">");
      Path input = fixture(payload, "image/svg+xml", R + "/image", "image.svg");
      var raw = new DocxReader().read(input);
      var parsed = new Docx4jDocumentParser().parse(raw);
      Path output = temp.resolve("opaque-annotated.docx");
      var selected = AnnotationFindings.select(new RuleEngine().check(parsed, RuleSet.schoolV1())
          .stream().filter(f -> f.ruleId().equals("NUMBERING-01")).toList()).representatives();
      var report = new CommentWriter().writeCopy(input, output, raw.sourceSha256(), selected);
      assertTrue(report.outputWritten());
      assertTrue(report.items().stream().allMatch(i -> i.status() == AnnotationStatus.WRITTEN));
      var outputRaw = new DocxReader().read(output);
      assertEquals(parsed.images(), new Docx4jDocumentParser().parse(outputRaw).images());
      assertMediaUnchanged(raw, outputRaw);
      assertEquals(0, requests.get(), "SVG is opaque media: neither its DTD nor its entity may be loaded");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void contentTypeRatherThanFileExtensionIdentifiesSvgMedia() throws Exception {
    Path input = fixture(svg(SVG_DOCTYPE), "image/svg+xml", R + "/image", "vector.dat");
    assertEquals(2, new Docx4jDocumentParser().parse(new DocxReader().read(input)).images().size());
  }

  @Test
  void svgFilenameDoesNotBypassXmlSafetyForAnotherContentType() throws Exception {
    Path input = fixture(svg(SVG_DOCTYPE), "application/xml", R + "/image", "image.svg");
    assertThrows(IllegalArgumentException.class,
        () -> new Docx4jDocumentParser().parse(new DocxReader().read(input)));
  }

  @Test
  void svgContentTypeDoesNotBypassXmlSafetyForANonImageRelationship() throws Exception {
    Path input = fixture(svg(SVG_DOCTYPE), "image/svg+xml", R + "/customXml", "image.svg");
    assertThrows(IllegalArgumentException.class,
        () -> new Docx4jDocumentParser().parse(new DocxReader().read(input)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"word/document.xml", "word/styles.xml", "word/_rels/document.xml.rels"})
  void xmlPackagePartsStillRejectDoctype(String name) throws Exception {
    var parts = parts(svg(SVG_DOCTYPE), "image/svg+xml", R + "/image", "image.svg");
    String original = new String(parts.get(name), StandardCharsets.UTF_8);
    String root = name.endsWith(".rels") ? "Relationships" : name.endsWith("styles.xml") ? "w:styles" : "w:document";
    parts.put(name, ("<!DOCTYPE " + root + " [<!ENTITY unsafe 'unsafe'>]>" + original)
        .getBytes(StandardCharsets.UTF_8));
    Path input = write(parts);
    assertEquals("INVALID_DOCX", assertThrows(InspectionException.class,
        () -> new DocxReader().read(input)).code());
  }

  private static String svg(String doctype) {
    return doctype + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\" height=\"10\">"
        + "<rect width=\"20\" height=\"10\" fill=\"black\"/></svg>";
  }

  private Path fixture(String svg, String contentType, String relationship, String filename) throws Exception {
    return write(parts(svg, contentType, relationship, filename));
  }

  private static Map<String, byte[]> parts(String svg, String contentType, String relationship, String filename) {
    var result = new LinkedHashMap<String, byte[]>();
    put(result, "[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
        + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
        + "<Override PartName=\"/word/media/" + filename + "\" ContentType=\"" + contentType + "\"/>"
        + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
        + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>");
    put(result, "_rels/.rels", "<Relationships xmlns=\"" + RELS + "\"><Relationship Id=\"main\" Type=\""
        + R + "/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
    put(result, "word/_rels/document.xml.rels", "<Relationships xmlns=\"" + RELS + "\">"
        + "<Relationship Id=\"styles\" Type=\"" + R + "/styles\" Target=\"styles.xml\"/>"
        + "<Relationship Id=\"svg\" Type=\"" + relationship + "\" Target=\"media/" + filename + "\"/>"
        + "<Relationship Id=\"png\" Type=\"" + R + "/image\" Target=\"media/fallback.png\"/></Relationships>");
    put(result, "word/styles.xml", "<w:styles xmlns:w=\"" + W + "\"><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
        + "<w:name w:val=\"Normal\"/><w:rPr><w:rFonts w:eastAsia=\"宋体\" w:ascii=\"Times New Roman\"/>"
        + "<w:sz w:val=\"24\"/></w:rPr></w:style></w:styles>");
    put(result, "word/document.xml", "<w:document xmlns:w=\"" + W + "\" xmlns:r=\"" + R
        + "\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
        + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
        + " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""
        + " xmlns:asvg=\"http://schemas.microsoft.com/office/drawing/2016/SVG/main\"><w:body>"
        + drawing(true, 914400, 457200) + caption(true, 24)
        + drawing(false, 1828800, 914400) + caption(false, 21) + "<w:sectPr/></w:body></w:document>");
    put(result, "word/media/" + filename, svg);
    result.put("word/media/fallback.png", PNG);
    return result;
  }

  private static String drawing(boolean svg, long width, long height) {
    return "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing><wp:inline>"
        + "<wp:extent cx=\"" + width + "\" cy=\"" + height + "\"/><wp:docPr id=\"" + (svg ? 1 : 2) + "\" name=\"figure\"/>"
        + "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic>"
        + "<pic:nvPicPr><pic:cNvPr id=\"" + (svg ? 1 : 2) + "\" name=\"figure\"/><pic:cNvPicPr/></pic:nvPicPr>"
        + "<pic:blipFill><a:blip r:embed=\"png\">"
        + (svg ? "<a:extLst><a:ext uri=\"{96DAC541-7B7A-43D3-8B79-37D633B846F1}\"><asvg:svgBlip r:embed=\"svg\"/></a:ext></a:extLst>" : "")
        + "</a:blip></pic:blipFill></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>";
  }

  private static String caption(boolean automatic, int size) {
    String number = automatic ? "<w:fldSimple w:instr=\" SEQ Figure \">" + run("1", size) + "</w:fldSimple>"
        : run("2", size);
    return "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>" + run("图3-", size) + number
        + run(automatic ? " 系统结构图" : " 普通图片", size) + "</w:p>";
  }

  private static String run(String text, int size) {
    return "<w:r><w:rPr><w:rFonts w:eastAsia=\"宋体\" w:ascii=\"Times New Roman\"/><w:sz w:val=\""
        + size + "\"/></w:rPr><w:t xml:space=\"preserve\">" + text + "</w:t></w:r>";
  }

  private static void put(Map<String, byte[]> parts, String name, String value) {
    parts.put(name, value.getBytes(StandardCharsets.UTF_8));
  }

  private Path write(Map<String, byte[]> parts) throws Exception {
    Path input = temp.resolve("source.docx");
    try (var zip = new ZipOutputStream(Files.newOutputStream(input))) {
      for (var part : parts.entrySet()) {
        zip.putNextEntry(new ZipEntry(part.getKey()));
        zip.write(part.getValue());
        zip.closeEntry();
      }
    }
    return input;
  }

  private static void assertMediaUnchanged(RawDocx before, RawDocx after) {
    var names = before.parts().keySet().stream().filter(name -> name.startsWith("word/media/")).toList();
    assertEquals(names.stream().sorted().toList(), after.parts().keySet().stream()
        .filter(name -> name.startsWith("word/media/")).sorted().toList());
    for (String name : names) assertArrayEquals(before.part(name).bytes(), after.part(name).bytes(), name);
  }

  private static List<String> text(ParsedDocument document) {
    return document.paragraphs().stream().map(p -> p.runs().stream().map(RunModel::text)
        .reduce("", String::concat)).toList();
  }
}
