package cn.luwneshacha.thesis.reporting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.luwneshacha.thesis.document.Docx4jDocumentParser;
import cn.luwneshacha.thesis.document.DocxReader;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rules.Judgement;
import cn.luwneshacha.thesis.rules.RuleEngine;
import cn.luwneshacha.thesis.rules.RuleSet;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the public DOCX -> parser -> rules -> Word comment path for direct VML OLE figures. */
class VisioCaptionAnnotationTest {
  @TempDir Path temp;

  @ParameterizedTest
  @ValueSource(strings = {"", " ", "  "})
  void checksCaptionSpacingWithoutApplyingBodySize(String separator) throws Exception {
    Path input = fixture(separator, "宋体", 21);
    var raw = new DocxReader().read(input);
    var parsed = new Docx4jDocumentParser().parse(raw);
    assertEquals(1, parsed.images().size());
    assertEquals("图3-4" + separator + "登录时序图", parsed.images().get(0).captionText());
    var source = parsed.images().get(0).captionSource();
    var results = new RuleEngine().check(parsed, RuleSet.schoolV1());
    var captionResults = results.stream().filter(r -> sameParagraph(r.source(), source)).toList();
    assertFalse(captionResults.stream().anyMatch(r -> r.ruleId().startsWith("BODY-")),
        "已绑定图片的图题不得套正文小四、缩进或行距规则");
    for (String rule : List.of("CAPTION-SIZE", "CAPTION-FONT")) {
      var formatting = captionResults.stream().filter(r -> r.ruleId().equals(rule)).toList();
      assertEquals(3, formatting.size(), "跨三个 run 的题注都要检查");
      assertTrue(formatting.stream().allMatch(r -> r.judgement() == Judgement.PASS), formatting.toString());
    }
    var spacing = captionResults.stream().filter(r -> r.ruleId().equals("FIG-03")).toList();
    assertEquals(1, spacing.size());
    assertEquals(separator.equals(" ") ? Judgement.PASS : Judgement.FAIL, spacing.get(0).judgement());
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("BODY-SIZE")
        && r.judgement() == Judgement.FAIL && r.actual().equals("21")),
        "普通正文仍须检查小四，不能全局放宽字号要求");

    if (separator.equals(" ")) return;
    Path annotated = temp.resolve("annotated.docx");
    var annotation = new CommentWriter().writeCopy(input, annotated, raw.sourceSha256(), captionResults.stream()
        .filter(r -> r.ruleId().startsWith("FIG-") || r.ruleId().startsWith("CAPTION-"))
        .filter(r -> r.judgement() == Judgement.FAIL).toList());
    assertTrue(annotation.outputWritten());
    assertEquals(1, annotation.items().size(), annotation.toString());
    assertEquals(AnnotationStatus.WRITTEN, annotation.items().get(0).status());
    try (var zip = new ZipFile(annotated.toFile())) {
      String comments = new String(zip.getInputStream(zip.getEntry("word/comments.xml")).readAllBytes(),
          StandardCharsets.UTF_8);
      assertTrue(comments.contains("图3-4 登录时序图"), comments);
      assertTrue(comments.contains("空格"), comments);
      assertFalse(comments.contains("小四"), comments);
    }
    var reread = new Docx4jDocumentParser().parse(new DocxReader().read(annotated));
    assertEquals(parsed.images().get(0).captionText(), reread.images().get(0).captionText());
  }

  @Test
  void stillRejectsWrongCaptionFontAndSize() throws Exception {
    var parsed = new Docx4jDocumentParser().parse(new DocxReader().read(fixture(" ", "黑体", 24)));
    assertEquals(1, parsed.images().size());
    var source = parsed.images().get(0).captionSource();
    var results = new RuleEngine().check(parsed, RuleSet.schoolV1()).stream()
        .filter(r -> sameParagraph(r.source(), source)).toList();
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("CAPTION-FONT")
        && r.judgement() == Judgement.FAIL));
    assertTrue(results.stream().anyMatch(r -> r.ruleId().equals("CAPTION-SIZE")
        && r.judgement() == Judgement.FAIL && r.expected().equals("21")));
  }

  private static boolean sameParagraph(SourceAddress a, SourceAddress b) {
    return a != null && b != null && a.partName().equals(b.partName())
        && a.elementPath().equals(b.elementPath());
  }

  private Path fixture(String separator, String eastAsiaFont, int size) throws Exception {
    String w = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    String r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    String types = "http://schemas.openxmlformats.org/package/2006/content-types";
    String relationships = "http://schemas.openxmlformats.org/package/2006/relationships";
    String captionRuns = List.of("图", "3-4", separator + "登录时序图").stream()
        .map(t -> "<w:r><w:rPr><w:sz w:val=\"" + size + "\"/><w:szCs w:val=\"" + size
            + "\"/></w:rPr><w:t xml:space=\"preserve\">" + t + "</w:t></w:r>")
        .reduce("", String::concat);
    String document = "<w:document xmlns:w=\"" + w + "\" xmlns:r=\"" + r
        + "\" xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:o=\"urn:schemas-microsoft-com:office:office\">"
        + "<w:body><w:p><w:r><w:t>第三章  系统设计</w:t></w:r></w:p>"
        + "<w:p><w:r><w:rPr><w:sz w:val=\"21\"/></w:rPr><w:t>登录过程如图3-4所示。</w:t></w:r></w:p>"
        + "<w:p><w:r><w:object><v:shape id=\"visio1\" style=\"width:100pt;height:80pt\">"
        + "<v:imagedata r:id=\"rIdImage\"/></v:shape><o:OLEObject ProgID=\"Visio.Drawing.15\" "
        + "ShapeID=\"visio1\" Type=\"Embed\"/></w:object></w:r></w:p>"
        + "<w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr>" + captionRuns + "</w:p>"
        + "<w:sectPr/></w:body></w:document>";
    Path path = temp.resolve("input.docx");
    try (var zip = new ZipOutputStream(Files.newOutputStream(path))) {
      entry(zip, "[Content_Types].xml", "<Types xmlns=\"" + types + "\">"
          + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
          + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
          + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
          + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>");
      entry(zip, "_rels/.rels", "<Relationships xmlns=\"" + relationships + "\"><Relationship Id=\"rIdMain\" "
          + "Type=\"" + r + "/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
      entry(zip, "word/document.xml", document);
      entry(zip, "word/_rels/document.xml.rels", "<Relationships xmlns=\"" + relationships + "\">"
          + "<Relationship Id=\"rIdStyles\" Type=\"" + r + "/styles\" Target=\"styles.xml\"/>"
          + "<Relationship Id=\"rIdImage\" Type=\"" + r + "/image\" Target=\"media/image.png\"/></Relationships>");
      entry(zip, "word/styles.xml", "<w:styles xmlns:w=\"" + w + "\"><w:docDefaults><w:rPrDefault><w:rPr>"
          + "<w:rFonts w:ascii=\"Times New Roman\" w:hAnsi=\"Times New Roman\" w:eastAsia=\"" + eastAsiaFont + "\"/>"
          + "<w:sz w:val=\"24\"/></w:rPr></w:rPrDefault></w:docDefaults></w:styles>");
      zip.putNextEntry(new ZipEntry("word/media/image.png"));
      zip.write(java.util.Base64.getDecoder().decode(
          "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j7l8AAAAASUVORK5CYII="));
      zip.closeEntry();
    }
    return path;
  }

  private static void entry(ZipOutputStream zip, String name, String xml) throws Exception {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(xml.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }
}
