package cn.luwneshacha.thesis.rendering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfLayoutReaderTest {
  @TempDir Path temp;

  @Test
  void extractsKeywordContinuationCoordinatesFromActualPdfText() throws Exception {
    Path pdf = temp.resolve("keywords.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      try (var output = new PDPageContentStream(document, page)) {
        write(output, "Keywords: " , 30, 700);
        write(output, "Alpha; Beta;", 100, 700);
        write(output, "Gamma", 100, 680);
      }
      document.save(pdf.toFile());
    }

    List<RenderedLayoutEvidence.TextLine> lines = new PdfLayoutReader().read(pdf, 5);
    var evidence = new RenderedLayoutEvidence(
        new RenderedLayoutEvidence.Provenance("available", "source", "pdf",
            "PDF fixture", "1", "2026-11-18T00:00:00Z", null, 1), lines);

    assertEquals(RenderedLayoutEvidence.KeywordStatus.ALIGNED,
        evidence.englishKeywordAlignment("Keywords: Alpha; Beta; Gamma").status(),
        () -> evidence.englishKeywordAlignment("Keywords: Alpha; Beta; Gamma") + " lines=" + lines);
  }

  @Test
  void readsRealPageGeometryWithoutClaimingVisibleDigitsArePageFields() throws Exception {
    Path pdf = temp.resolve("page-numbers.pdf");
    try (var document = new PDDocument()) {
      for (int number = 1; number <= 2; number++) {
        var page = new PDPage(PDRectangle.LETTER);
        document.addPage(page);
        try (var output = new PDPageContentStream(document, page)) {
          write(output, number == 1 ? "First Chapter" : "Body text", 40, 700);
          write(output, Integer.toString(number), 303, 36);
        }
      }
      document.save(pdf.toFile());
    }

    var read = new PdfLayoutReader().readWithPageCount(pdf, 5);
    assertEquals(List.of(new RenderedLayoutEvidence.PageBox(1, 612, 792),
        new RenderedLayoutEvidence.PageBox(2, 612, 792)), read.pageBoxes());
    assertEquals(2, read.lines().stream().filter(line -> line.glyphs().stream()
        .anyMatch(glyph -> glyph.text().equals("1") || glyph.text().equals("2"))).count());
  }

  @Test
  void rejectsPdfWhoseExtractedTextExceedsTheConfiguredPositionLimit() throws Exception {
    Path pdf = temp.resolve("too-much-text.pdf");
    try (var document = new PDDocument()) {
      var page = new PDPage();
      document.addPage(page);
      try (var output = new PDPageContentStream(document, page)) {
        write(output, "ABCDEFGHIJKLMNOPQRSTUVWXYZ", 30, 700);
      }
      document.save(pdf.toFile());
    }

    assertThrows(IOException.class, () -> new PdfLayoutReader(10).read(pdf, 5));
  }

  @Test
  void usesIndividualGlyphWidthsAndKeepsUnsplittableLigaturesTogether() {
    var split = PdfLayoutReader.positionedGlyphs("Wi", 30, 12, new float[] {9, 3});
    assertEquals(30, split.get(0).x());
    assertEquals(39, split.get(1).x());
    assertEquals(3, split.get(1).width());

    var ligature = PdfLayoutReader.positionedGlyphs("fi", 30, 6, new float[] {6});
    assertEquals(List.of(new RenderedLayoutEvidence.Glyph("fi", 30, 6)), ligature);
  }

  private static void write(PDPageContentStream output, String text, int x, int y) throws Exception {
    output.beginText();
    output.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
    output.newLineAtOffset(x, y);
    output.showText(text);
    output.endText();
  }
}
