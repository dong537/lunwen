package cn.luwneshacha.thesis.rendering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfLayoutWorkerTest {
  @TempDir Path temp;

  @Test
  void exchangesRealPdfGeometryThroughBoundedEvidence() throws Exception {
    Path pdf = samplePdf();
    Path evidence = temp.resolve("geometry.bin");

    PdfLayoutWorker.extract(pdf, evidence, 5);
    var result = PdfLayoutWorker.read(evidence, 5);

    assertEquals(1, result.pageCount());
    assertEquals(1, result.pageBoxes().size());
    assertTrue(result.lines().stream().flatMap(line -> line.glyphs().stream())
        .anyMatch(glyph -> "A".equals(glyph.text())));
  }

  @Test
  void rejectsTruncatedAndUnknownVersionEvidence() throws Exception {
    Path evidence = temp.resolve("geometry.bin");
    PdfLayoutWorker.extract(samplePdf(), evidence, 5);
    byte[] valid = Files.readAllBytes(evidence);

    Files.write(evidence, Arrays.copyOf(valid, valid.length - 1));
    assertThrows(IOException.class, () -> PdfLayoutWorker.read(evidence, 5));

    byte[] changed = valid.clone();
    ByteBuffer.wrap(changed).order(ByteOrder.BIG_ENDIAN).putInt(4, 99);
    Files.write(evidence, changed);
    assertThrows(IOException.class, () -> PdfLayoutWorker.read(evidence, 5));
  }

  @Test
  void rejectsNonFiniteCoordinatesAndOversizedEvidence() throws Exception {
    Path evidence = temp.resolve("geometry.bin");
    PdfLayoutWorker.extract(samplePdf(), evidence, 5);
    byte[] changed = Files.readAllBytes(evidence);
    // Header: magic, version and page count; then the first page width.
    ByteBuffer.wrap(changed).order(ByteOrder.BIG_ENDIAN).putDouble(12, Double.NaN);
    Files.write(evidence, changed);
    assertThrows(IOException.class, () -> PdfLayoutWorker.read(evidence, 5));

    byte[] overCount = Files.readAllBytes(evidence);
    // One page contributes two doubles; the next field is the line count.
    ByteBuffer.wrap(overCount).order(ByteOrder.BIG_ENDIAN).putDouble(12, 612);
    ByteBuffer.wrap(overCount).order(ByteOrder.BIG_ENDIAN).putInt(28, 500_001);
    Files.write(evidence, overCount);
    assertThrows(IOException.class, () -> PdfLayoutWorker.read(evidence, 5));

    try (var file = Files.newByteChannel(evidence, StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING)) {
      file.position(64L * 1024 * 1024);
      file.write(ByteBuffer.wrap(new byte[] {1}));
    }
    assertThrows(IOException.class, () -> PdfLayoutWorker.read(evidence, 5));
  }

  @Test
  void rejectsTextCoordinatesOutsideTheRenderedPageBox() throws Exception {
    Path evidence = temp.resolve("geometry.bin");
    PdfLayoutWorker.extract(samplePdf(), evidence, 5);
    byte[] valid = Files.readAllBytes(evidence);

    byte[] outsideX = valid.clone();
    ByteBuffer.wrap(outsideX).order(ByteOrder.BIG_ENDIAN).putDouble(48, 700);
    Files.write(evidence, outsideX);
    assertThrows(IOException.class, () -> PdfLayoutWorker.read(evidence, 5));

    byte[] outsideY = valid.clone();
    ByteBuffer.wrap(outsideY).order(ByteOrder.BIG_ENDIAN).putDouble(36, 900);
    Files.write(evidence, outsideY);
    assertThrows(IOException.class, () -> PdfLayoutWorker.read(evidence, 5));

    byte[] outsideWidth = valid.clone();
    ByteBuffer.wrap(outsideWidth).order(ByteOrder.BIG_ENDIAN).putDouble(56, 700);
    Files.write(evidence, outsideWidth);
    assertThrows(IOException.class, () -> PdfLayoutWorker.read(evidence, 5));
  }

  @Test
  void childJvmWritesEvidenceAndExitsNonzeroForInvalidLimit() throws Exception {
    Path pdf = samplePdf();
    Path evidence = temp.resolve("child.bin");
    var valid = launch(pdf, evidence, 5);
    assertEquals(0, valid.exitCode(), valid.output());
    assertEquals(1, PdfLayoutWorker.read(evidence, 5).pageCount());

    Files.delete(evidence);
    var invalid = launch(pdf, evidence, 0);
    assertTrue(invalid.exitCode() != 0);
    assertTrue(Files.notExists(evidence));
  }

  private ChildResult launch(Path pdf, Path evidence, int maxPages) throws Exception {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var process = new ProcessBuilder(java, "-Xmx128m", "-XX:MaxDirectMemorySize=32m",
        "-cp", System.getProperty("java.class.path"), PdfLayoutWorker.class.getName(),
        pdf.toString(), evidence.toString(), Integer.toString(maxPages))
        .redirectErrorStream(true).start();
    assertTrue(process.waitFor(20, TimeUnit.SECONDS), "PDF worker did not finish");
    return new ChildResult(process.exitValue(), new String(process.getInputStream().readAllBytes()));
  }

  private record ChildResult(int exitCode, String output) {}

  private Path samplePdf() throws Exception {
    Path pdf = temp.resolve("sample.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      try (var output = new PDPageContentStream(document, page)) {
        output.beginText();
        output.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        output.newLineAtOffset(30, 700);
        output.showText("Alpha");
        output.endText();
      }
      document.save(pdf.toFile());
    }
    return pdf;
  }
}
