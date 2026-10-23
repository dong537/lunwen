package cn.luwneshacha.platform.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfSummaryReportWriterTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path temp;

  @Test void writesOnePageWithEmbeddedChineseFontAndSeparateReviewCount() throws Exception {
    Path font = configuredFont();
    Assumptions.assumeTrue(font != null && Files.isRegularFile(font), "CJK font is required for this test");
    Path output = temp.resolve("summary.pdf");
    PdfSummaryReportWriter writer = new PdfSummaryReportWriter(JSON, font.toString());

    writer.write(json(), metadata(), output);

    assertTrue(Files.size(output) > 0);
    try (PDDocument document = PDDocument.load(output.toFile())) {
      assertEquals(1, document.getNumberOfPages());
      BufferedImage rendered = new PDFRenderer(document).renderImage(0);
      assertTrue(nonWhitePixels(rendered) > 500, "rendered report must contain visible text");
      boolean hasEmbeddedFont = false;
      for (var name : document.getPage(0).getResources().getFontNames()) {
        hasEmbeddedFont |= document.getPage(0).getResources().getFont(name).isEmbedded();
      }
      assertTrue(hasEmbeddedFont);
      String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
      assertTrue(text.contains("\u8bba\u6587\u5ba1\u6838\u7ed3\u679c\u5355"), text.codePoints().mapToObj(Integer::toHexString).toList().toString());
      assertTrue(text.contains("\u660e\u786e\u95ee\u9898"));
      assertTrue(text.contains("\u5f85\u786e\u8ba4"));
      assertTrue(text.contains("1 \u9879"));
      assertTrue(text.contains("2 \u9879"));
      assertTrue(text.contains("\u5b66\u751f\u7532"));
      for (String line : text.split("\\R")) {
        assertFalse(line.stripLeading().matches("^[，。；：！？、…）》】』」]"), "孤立标点不能出现在行首: " + line);
      }
      assertTrue(usesBoldRendering(document), "emphasized text must use a visibly heavier PDF rendering mode");
    }
  }

  @Test void rejectsAnExplicitMissingFontInsteadOfGeneratingUnembeddedText() {
    PdfSummaryReportWriter writer = new PdfSummaryReportWriter(JSON, temp.resolve("missing.otf").toString());
    assertThrows(java.io.IOException.class, () -> writer.write(json(), metadata(), temp.resolve("missing.pdf")));
  }

  @Test void rejectsAvailableAnnotationStatusWithoutItemsInsteadOfReportingZeroWritten() throws Exception {
    byte[] incomplete = JSON.writeValueAsBytes(JSON.readTree("""
        {"report":{"results":[]},"annotation":{"available":true}}
        """));
    Path font = configuredFont();
    Assumptions.assumeTrue(font != null && Files.isRegularFile(font), "CJK font is required for this test");
    PdfSummaryReportWriter writer = new PdfSummaryReportWriter(JSON, font.toString());

    assertThrows(java.io.IOException.class, () -> writer.write(incomplete, metadata(), temp.resolve("incomplete.pdf")));
  }

  @Test void keepsMaximumLengthChineseMetadataOnOnePageAndMarksTruncation() throws Exception {
    Path font = configuredFont();
    Assumptions.assumeTrue(font != null && Files.isRegularFile(font), "CJK font is required for this test");
    var metadata = new PdfSummaryReportWriter.Metadata("论".repeat(250) + ".docx", 1,
        Instant.parse("2026-10-23T03:04:05Z"),
        new PdfSummaryReportWriter.StudentInfo("学".repeat(64), "2026123456", "专".repeat(64), "导".repeat(64)));
    Path output = temp.resolve("long-metadata.pdf");

    new PdfSummaryReportWriter(JSON, font.toString()).write(json(), metadata, output);

    try (PDDocument document = PDDocument.load(output.toFile())) {
      assertEquals(1, document.getNumberOfPages());
      String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
      assertTrue(text.contains("…"));
    }
  }

  private static byte[] json() throws Exception {
    return JSON.writeValueAsBytes(JSON.readTree("""
        {"report":{"sourceSha256":"0123456789abcdef0123456789abcdef","ruleSetId":"school-v3","results":[
        {"judgement":"FAIL"},{"judgement":"REVIEW"},{"judgement":"REVIEW"},{"judgement":"PASS"}]},
        "annotation":{"available":true,"items":[{"status":"WRITTEN"},{"status":"WRITTEN"},{"status":"UNWRITTEN"}]}}
        """));
  }

  private static PdfSummaryReportWriter.Metadata metadata() {
    return new PdfSummaryReportWriter.Metadata("\u8bba\u6587.docx", 2, Instant.parse("2026-10-23T03:04:05Z"),
        new PdfSummaryReportWriter.StudentInfo("\u5b66\u751f\u7532", "2026123456", "\u8ba1\u7b97\u673a\u79d1\u5b66\u4e0e\u6280\u672f", "\u674e\u8001\u5e08"));
  }

  private static Path configuredFont() {
    String configured = System.getProperty("thesis.pdf.font", "").trim();
    if (!configured.isBlank()) return Path.of(configured);
    Path windows = Path.of("C:/Windows/Fonts/simsun.ttc");
    if (Files.isRegularFile(windows)) return windows;
    Path wqy = Path.of("/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc");
    if (Files.isRegularFile(wqy)) return wqy;
    Path linuxCollection = Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc");
    if (Files.isRegularFile(linuxCollection)) return linuxCollection;
    Path linux = Path.of("/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.otf");
    return Files.isRegularFile(linux) ? linux : null;
  }

  private static long nonWhitePixels(BufferedImage image) {
    long count = 0;
    for (int y = 0; y < image.getHeight(); y++) {
      for (int x = 0; x < image.getWidth(); x++) {
        if ((image.getRGB(x, y) & 0x00ffffff) != 0x00ffffff) count++;
      }
    }
    return count;
  }

  private static boolean usesBoldRendering(PDDocument document) throws Exception {
    PDFStreamParser parser = new PDFStreamParser(document.getPage(0));
    parser.parse();
    var tokens = parser.getTokens();
    for (int index = 1; index < tokens.size(); index++) {
      if (tokens.get(index) instanceof Operator operator && operator.getName().equals("Tr")
          && tokens.get(index - 1) instanceof COSNumber mode && mode.intValue() == 2) return true;
    }
    return false;
  }
}
