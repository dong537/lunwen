package cn.luwneshacha.thesis.rendering;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Extracts PDF geometry in a separately bounded JVM. */
public final class PdfLayoutWorker {
  private static final int MAGIC = 0x544C4159;
  private static final int VERSION = 1;
  private static final int MAX_TEXT_POSITIONS = PdfLayoutReader.DEFAULT_MAX_TEXT_POSITIONS;
  private static final int MAX_GLYPH_BYTES = 128;
  private static final long MAX_EVIDENCE_BYTES = 64L * 1024 * 1024;
  private PdfLayoutWorker() {}

  static void extract(Path pdf, Path evidence, int maxPages) throws IOException {
    if (maxPages < 1) throw new IllegalArgumentException("maxPages must be positive");
    var result = new PdfLayoutReader().readWithPageCount(pdf, maxPages);
    Path pending = evidence.resolveSibling(evidence.getFileName() + ".part");
    try {
      try (var output = new DataOutputStream(new BoundedOutputStream(
          Files.newOutputStream(pending), MAX_EVIDENCE_BYTES))) {
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeInt(result.pageCount());
        for (var box : result.pageBoxes()) {
          output.writeDouble(box.width());
          output.writeDouble(box.height());
        }
        output.writeInt(result.lines().size());
        for (var line : result.lines()) {
          output.writeInt(line.page());
          output.writeDouble(line.y());
          output.writeInt(line.glyphs().size());
          for (var glyph : line.glyphs()) {
            output.writeDouble(glyph.x());
            output.writeDouble(glyph.width());
            byte[] characters = glyph.text().getBytes(StandardCharsets.UTF_8);
            if (characters.length < 1 || characters.length > MAX_GLYPH_BYTES)
              throw new IOException("PDF glyph text is outside the evidence limit");
            output.writeInt(characters.length);
            output.write(characters);
          }
        }
      }
      Files.move(pending, evidence, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(pending);
    }
  }

  static PdfLayoutReader.ReadResult read(Path evidence, int maxPages) throws IOException {
    if (maxPages < 1) throw new IllegalArgumentException("maxPages must be positive");
    long size = Files.size(evidence);
    if (size < 20 || size > MAX_EVIDENCE_BYTES)
      throw new IOException("PDF evidence file is outside the configured limit");
    try (var input = new DataInputStream(Files.newInputStream(evidence))) {
      if (input.readInt() != MAGIC || input.readInt() != VERSION)
        throw new IOException("PDF evidence version is unsupported");
      int pages = input.readInt();
      if (pages < 1 || pages > maxPages)
        throw new IOException("PDF evidence page count is outside the configured limit");
      var boxes = new ArrayList<RenderedLayoutEvidence.PageBox>(pages);
      for (int page = 1; page <= pages; page++) {
        double width = input.readDouble();
        double height = input.readDouble();
        if (!positiveCoordinate(width) || !positiveCoordinate(height))
          throw new IOException("PDF evidence page geometry is invalid");
        boxes.add(new RenderedLayoutEvidence.PageBox(page, width, height));
      }
      int lineCount = input.readInt();
      if (lineCount < 0 || lineCount > MAX_TEXT_POSITIONS)
        throw new IOException("PDF evidence line count is outside the configured limit");
      int remainingGlyphs = MAX_TEXT_POSITIONS;
      int remainingCharacters = MAX_TEXT_POSITIONS;
      var lines = new ArrayList<RenderedLayoutEvidence.TextLine>(lineCount);
      for (int index = 0; index < lineCount; index++) {
        int page = input.readInt();
        double y = input.readDouble();
        int glyphCount = input.readInt();
        if (page < 1 || page > pages || !Double.isFinite(y)
            || glyphCount < 1 || glyphCount > remainingGlyphs)
          throw new IOException("PDF evidence line is invalid");
        var box = boxes.get(page - 1);
        if (y < 0 || y > box.height())
          throw new IOException("PDF evidence text is outside the page");
        remainingGlyphs -= glyphCount;
        var glyphs = new ArrayList<RenderedLayoutEvidence.Glyph>(glyphCount);
        for (int glyphIndex = 0; glyphIndex < glyphCount; glyphIndex++) {
          double x = input.readDouble();
          double width = input.readDouble();
          int length = input.readInt();
          if (!Double.isFinite(x) || !Double.isFinite(width) || x < 0
              || x > box.width() || width < 0 || width > box.width() - x
              || length < 1 || length > MAX_GLYPH_BYTES)
            throw new IOException("PDF evidence glyph is invalid");
          byte[] bytes = input.readNBytes(length);
          if (bytes.length != length) throw new IOException("PDF evidence glyph is truncated");
          String characters = decodeUtf8(bytes);
          int count = characters.codePointCount(0, characters.length());
          if (count < 1 || count > remainingCharacters)
            throw new IOException("PDF evidence text count exceeded configured limit");
          remainingCharacters -= count;
          glyphs.add(new RenderedLayoutEvidence.Glyph(characters, x, width));
        }
        lines.add(new RenderedLayoutEvidence.TextLine(page, y, glyphs));
      }
      if (input.read() != -1) throw new IOException("PDF evidence contains trailing data");
      return new PdfLayoutReader.ReadResult(lines, pages, boxes);
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 3) throw new IllegalArgumentException("PDF worker requires input, output and maxPages");
    extract(Path.of(args[0]), Path.of(args[1]), Integer.parseInt(args[2]));
  }

  private static boolean positiveCoordinate(double value) {
    return Double.isFinite(value) && value > 0 && value <= 100_000;
  }

  private static String decodeUtf8(byte[] bytes) throws IOException {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes)).toString();
    } catch (CharacterCodingException error) {
      throw new IOException("PDF evidence glyph is not valid UTF-8", error);
    }
  }

  private static final class BoundedOutputStream extends FilterOutputStream {
    private final long maxBytes;
    private long written;

    private BoundedOutputStream(OutputStream output, long maxBytes) {
      super(output);
      this.maxBytes = maxBytes;
    }

    @Override public void write(int value) throws IOException {
      if (written >= maxBytes) throw new IOException("PDF evidence output exceeded configured limit");
      out.write(value);
      written++;
    }

    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
      if (length > maxBytes - written)
        throw new IOException("PDF evidence output exceeded configured limit");
      out.write(bytes, offset, length);
      written += length;
    }
  }
}
