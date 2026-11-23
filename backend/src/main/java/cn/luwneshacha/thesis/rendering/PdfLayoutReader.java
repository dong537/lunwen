package cn.luwneshacha.thesis.rendering;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Extracts positioned text from a bounded, already converted PDF. */
public final class PdfLayoutReader {
  private static final double LINE_BASELINE_PRECISION_POINTS = 2;
  static final int DEFAULT_MAX_TEXT_POSITIONS = 500_000;
  private final int maxTextPositions;

  public PdfLayoutReader() { this(DEFAULT_MAX_TEXT_POSITIONS); }

  PdfLayoutReader(int maxTextPositions) {
    if (maxTextPositions < 1) throw new IllegalArgumentException("maxTextPositions must be positive");
    this.maxTextPositions = maxTextPositions;
  }

  public List<RenderedLayoutEvidence.TextLine> read(Path pdf, int maxPages) throws IOException {
    return readWithPageCount(pdf, maxPages).lines();
  }

  public ReadResult readWithPageCount(Path pdf, int maxPages) throws IOException {
    if (maxPages < 1) throw new IllegalArgumentException("maxPages must be positive");
    try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
      int pages = document.getNumberOfPages();
      if (pages < 1 || pages > maxPages) throw new IOException("PDF page count is outside the configured limit");
      var stripper = new PositionCollector(maxTextPositions);
      stripper.setSortByPosition(true);
      try { stripper.getText(document); }
      catch (TextPositionLimitExceeded error) {
        throw new IOException("PDF text position count exceeded configured limit", error);
      }
      var pageBoxes = new ArrayList<RenderedLayoutEvidence.PageBox>(pages);
      for (int index = 0; index < pages; index++) {
        var page = document.getPage(index);
        var crop = page.getCropBox();
        boolean rotated = Math.floorMod(page.getRotation(), 180) == 90;
        pageBoxes.add(new RenderedLayoutEvidence.PageBox(index + 1,
            rotated ? crop.getHeight() : crop.getWidth(),
            rotated ? crop.getWidth() : crop.getHeight()));
      }
      return new ReadResult(lines(stripper.positions), pages, pageBoxes);
    }
  }

  private static List<RenderedLayoutEvidence.TextLine> lines(List<Position> positions) {
    var ordered = positions.stream().sorted(Comparator.comparingInt(Position::page)
        .thenComparingDouble(Position::y).thenComparingDouble(Position::x)).toList();
    var output = new ArrayList<RenderedLayoutEvidence.TextLine>();
    int page = -1;
    double y = Double.NaN;
    var glyphs = new ArrayList<RenderedLayoutEvidence.Glyph>();
    for (Position position : ordered) {
      if (page != position.page() || Math.abs(y - position.y()) > LINE_BASELINE_PRECISION_POINTS) {
        if (!glyphs.isEmpty()) output.add(new RenderedLayoutEvidence.TextLine(page, y, glyphs));
        page = position.page();
        y = position.y();
        glyphs = new ArrayList<>();
      }
      glyphs.add(new RenderedLayoutEvidence.Glyph(position.text(), position.x(), position.width()));
    }
    if (!glyphs.isEmpty()) output.add(new RenderedLayoutEvidence.TextLine(page, y, glyphs));
    return List.copyOf(output);
  }

  public record ReadResult(List<RenderedLayoutEvidence.TextLine> lines, int pageCount,
      List<RenderedLayoutEvidence.PageBox> pageBoxes) {
    public ReadResult {
      lines = List.copyOf(lines);
      pageBoxes = List.copyOf(pageBoxes);
    }
  }

  private record Position(int page, double x, double y, double width, String text) {}

  static List<RenderedLayoutEvidence.Glyph> positionedGlyphs(String characters, double x,
      double width, float[] individualWidths) {
    int count = characters.codePointCount(0, characters.length());
    if (count < 2 || individualWidths == null || individualWidths.length != count
        || !Double.isFinite(x) || !Double.isFinite(width) || width < 0)
      return List.of(new RenderedLayoutEvidence.Glyph(characters, x, width));
    double total = 0;
    for (float individualWidth : individualWidths) {
      if (!Float.isFinite(individualWidth) || individualWidth <= 0)
        return List.of(new RenderedLayoutEvidence.Glyph(characters, x, width));
      total += individualWidth;
    }
    if (!Double.isFinite(total) || total <= 0)
      return List.of(new RenderedLayoutEvidence.Glyph(characters, x, width));
    var glyphs = new ArrayList<RenderedLayoutEvidence.Glyph>(count);
    double currentX = x;
    int offset = 0;
    for (float individualWidth : individualWidths) {
      int codePoint = characters.codePointAt(offset);
      double glyphWidth = width * individualWidth / total;
      glyphs.add(new RenderedLayoutEvidence.Glyph(new String(Character.toChars(codePoint)),
          currentX, glyphWidth));
      currentX += glyphWidth;
      offset += Character.charCount(codePoint);
    }
    return List.copyOf(glyphs);
  }

  private static final class PositionCollector extends PDFTextStripper {
    private final List<Position> positions = new ArrayList<>();
    private final int maxTextPositions;
    private int seenTextPositions;
    private int extractedCharacters;

    private PositionCollector(int maxTextPositions) throws IOException {
      this.maxTextPositions = maxTextPositions;
    }

    @Override
    protected void processTextPosition(TextPosition position) {
      if (++seenTextPositions > maxTextPositions) throw new TextPositionLimitExceeded();
      super.processTextPosition(position);
    }

    @Override
    protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
      for (TextPosition position : textPositions) {
        String characters = position.getUnicode();
        if (characters == null || characters.isEmpty()) continue;
        int length = characters.codePointCount(0, characters.length());
        if (length > maxTextPositions - extractedCharacters)
          throw new IOException("PDF extracted character count exceeded configured limit");
        extractedCharacters += length;
        for (var glyph : positionedGlyphs(characters, position.getXDirAdj(),
            position.getWidthDirAdj(), position.getIndividualWidths()))
          positions.add(new Position(getCurrentPageNo(), glyph.x(), position.getYDirAdj(),
              glyph.width(), glyph.text()));
      }
    }
  }

  private static final class TextPositionLimitExceeded extends RuntimeException {}
}
