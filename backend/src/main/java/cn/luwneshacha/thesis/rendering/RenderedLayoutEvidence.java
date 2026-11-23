package cn.luwneshacha.thesis.rendering;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Immutable PDF text geometry. A finding is usable only after a unique full-paragraph match. */
public record RenderedLayoutEvidence(Provenance provenance, List<TextLine> lines, List<PageBox> pages) {
  private static final double MAX_LINE_GAP_POINTS = 36;
  private static final double ALIGNMENT_PRECISION_POINTS = 1;

  public RenderedLayoutEvidence {
    Objects.requireNonNull(provenance, "provenance");
    lines = List.copyOf(lines);
    pages = List.copyOf(pages);
  }

  public RenderedLayoutEvidence(Provenance provenance, List<TextLine> lines) {
    this(provenance, lines, List.of());
  }

  public boolean matchesSource(String sourceSha256) {
    return "available".equals(provenance.status())
        && provenance.sourceSha256().equals(sourceSha256);
  }

  public KeywordDecision englishKeywordAlignment(String originalParagraph) {
    if (!"available".equals(provenance.status())) return unknown("RENDER_UNAVAILABLE");
    String target = normalized(originalParagraph);
    if (!target.startsWith("keywords:") || target.length() <= "keywords:".length())
      return unknown("KEYWORD_TEXT_NOT_MATCHABLE");
    var matches = matches(target, true);
    if (matches.size() != 1) return unknown("KEYWORD_RENDER_MAPPING_NOT_UNIQUE");
    List<TextLine> match = matches.get(0);
    if (match.size() == 1) return new KeywordDecision(KeywordStatus.NO_WRAP, match.get(0).page(), null, null, null);
    Double keywordX = firstKeywordX(match.get(0));
    if (keywordX == null) return unknown("KEYWORD_FIRST_GLYPH_NOT_LOCATED");
    for (int i = 1; i < match.size(); i++) {
      Double continuationX = match.get(i).firstVisibleX();
      if (continuationX == null) return unknown("KEYWORD_CONTINUATION_NOT_LOCATED");
      if (Math.abs(keywordX - continuationX) > ALIGNMENT_PRECISION_POINTS)
        return new KeywordDecision(KeywordStatus.MISALIGNED, match.get(i).page(), keywordX,
            continuationX, null);
    }
    return new KeywordDecision(KeywordStatus.ALIGNED, match.get(0).page(), keywordX,
        match.get(1).firstVisibleX(), null);
  }

  public ChapterDecision chapterStart(String chapterHeading, String previousParagraph) {
    if (!"available".equals(provenance.status()) || normalized(chapterHeading).isEmpty()
        || normalized(previousParagraph).isEmpty()) return new ChapterDecision(ChapterStatus.UNKNOWN, null);
    var headings = matches(normalized(chapterHeading), false);
    var previous = matches(normalized(previousParagraph), false);
    if (headings.size() != 1 || previous.size() != 1)
      return new ChapterDecision(ChapterStatus.UNKNOWN, null);
    TextLine headingStart = headings.get(0).get(0);
    TextLine previousEnd = previous.get(0).getLast();
    if (headingStart.page() > previousEnd.page())
      return new ChapterDecision(ChapterStatus.NEW_PAGE, headingStart.page());
    if (headingStart.page() == previousEnd.page() && headingStart.y() > previousEnd.y())
      return new ChapterDecision(ChapterStatus.SAME_PAGE, headingStart.page());
    return new ChapterDecision(ChapterStatus.UNKNOWN, null);
  }

  /** A continuation label is checked only when its full rendered line maps uniquely. */
  public ContinuationPositionDecision continuationPosition(String caption, List<String> headerTexts) {
    if (!"available".equals(provenance.status()) || normalized(caption).isEmpty())
      return new ContinuationPositionDecision(ContinuationPositionStatus.UNKNOWN, null, null);
    String target = normalized(caption);
    List<TextLine> matches = lines.stream()
        .filter(line -> target.equals(normalized(line.text()))).toList();
    if (matches.size() != 1) return new ContinuationPositionDecision(
        ContinuationPositionStatus.UNKNOWN, null, null);
    TextLine captionLine = matches.getFirst();
    if (captionLine.page() < 1 || captionLine.page() > provenance.pageCount()
        || !Double.isFinite(captionLine.y()))
      return new ContinuationPositionDecision(ContinuationPositionStatus.UNKNOWN, null, null);
    List<String> normalizedHeaders = headerTexts.stream().map(RenderedLayoutEvidence::normalized)
        .filter(text -> !text.isEmpty()).toList();
    List<String> preceding = lines.stream()
        .filter(line -> line.page() == captionLine.page() && Double.isFinite(line.y())
            && line.y() < captionLine.y())
        .sorted(Comparator.comparingDouble(TextLine::y))
        .map(line -> normalized(line.text()))
        .filter(text -> !text.isEmpty()).toList();
    int start = !preceding.isEmpty() && normalizedHeaders.contains(preceding.getFirst()) ? 1 : 0;
    long precedingBodyLines = preceding.size() - start;
    return new ContinuationPositionDecision(precedingBodyLines == 0
        ? ContinuationPositionStatus.FIRST_BODY_LINE
        : ContinuationPositionStatus.LATER_BODY_LINE,
        captionLine.page(), Math.toIntExact(precedingBodyLines + 1));
  }

  /** Visual candidates only: these are not bound to a DOCX section or PAGE field. */
  public List<PageBottomNumberCandidate> pageBottomNumberCandidates() {
    if (!"available".equals(provenance.status())) return List.of();
    var linesByPage = new HashMap<Integer, List<TextLine>>();
    for (TextLine line : lines) linesByPage.computeIfAbsent(line.page(), ignored -> new ArrayList<>()).add(line);
    var candidates = new ArrayList<PageBottomNumberCandidate>();
    for (PageBox page : pages) {
      if (page.page() < 1 || page.page() > provenance.pageCount()
          || !Double.isFinite(page.width()) || page.width() <= 0
          || !Double.isFinite(page.height()) || page.height() <= 0) continue;
      PageBottomNumberCandidate unique = null;
      boolean ambiguous = false;
      for (TextLine line : linesByPage.getOrDefault(page.page(), List.of())) {
        if (line.page() != page.page() || !Double.isFinite(line.y())
            || line.y() < page.height() - 100 || line.y() > page.height()) continue;
        String value = line.text().strip();
        if (!value.matches("[0-9]{1,6}")) continue;
        double left = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        for (Glyph glyph : line.glyphs()) {
          if (!Double.isFinite(glyph.x()) || !Double.isFinite(glyph.width())
              || glyph.width() < 0) {
            left = Double.NaN;
            break;
          }
          left = Math.min(left, glyph.x());
          right = Math.max(right, glyph.x() + glyph.width());
        }
        double center = (left + right) / 2;
        if (!Double.isFinite(center) || Math.abs(center - page.width() / 2) > page.width() / 20)
          continue;
        if (unique != null) {
          ambiguous = true;
          break;
        }
        unique = new PageBottomNumberCandidate(page.page(), value, center, line.y());
      }
      if (!ambiguous && unique != null) candidates.add(unique);
    }
    return List.copyOf(candidates);
  }

  private List<List<TextLine>> matches(String target, boolean samePage) {
    var ordered = lines.stream().sorted(Comparator.comparingInt(TextLine::page)
        .thenComparingDouble(TextLine::y)).toList();
    var found = new ArrayList<List<TextLine>>();
    for (int i = 0; i < ordered.size(); i++) {
      StringBuilder candidate = new StringBuilder();
      var matchedLines = new ArrayList<TextLine>();
      for (int j = i; j < ordered.size(); j++) {
        TextLine line = ordered.get(j);
        if (j > i) {
          TextLine prior = ordered.get(j - 1);
          if (line.page() > prior.page() + 1 || (samePage && line.page() != prior.page())
              || (line.page() == prior.page() && line.y() - prior.y() > MAX_LINE_GAP_POINTS)) break;
        }
        candidate.append(normalized(line.text()));
        if (!target.startsWith(candidate.toString())) break;
        matchedLines.add(line);
        if (candidate.toString().equals(target)) {
          found.add(List.copyOf(matchedLines));
          break;
        }
      }
    }
    return found;
  }

  private static KeywordDecision unknown(String reason) {
    return new KeywordDecision(KeywordStatus.UNKNOWN, null, null, null, reason);
  }

  private static Double firstKeywordX(TextLine line) {
    StringBuilder prefix = new StringBuilder();
    boolean labelEnded = false;
    for (Glyph glyph : line.glyphs()) {
      String value = normalized(glyph.text());
      if (value.isEmpty()) continue;
      if (labelEnded) return Double.isFinite(glyph.x()) ? glyph.x() : null;
      prefix.append(value);
      if (prefix.toString().equals("keywords:")) labelEnded = true;
      else if (!"keywords:".startsWith(prefix.toString())) return null;
    }
    return null;
  }

  private static String normalized(String value) {
    String nfkc = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
        .toLowerCase(Locale.ROOT);
    return nfkc.codePoints().filter(code -> !Character.isWhitespace(code) && !Character.isSpaceChar(code)
        && code != 0x200B && code != 0x200C && code != 0x200D && code != 0x2060)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }

  public enum KeywordStatus { ALIGNED, MISALIGNED, NO_WRAP, UNKNOWN }

  public enum ChapterStatus { NEW_PAGE, SAME_PAGE, UNKNOWN }

  public enum ContinuationPositionStatus { FIRST_BODY_LINE, LATER_BODY_LINE, UNKNOWN }

  public record ContinuationPositionDecision(ContinuationPositionStatus status,
      Integer page, Integer bodyLineNumber) {}

  public record ChapterDecision(ChapterStatus status, Integer page) {}

  public record PageBox(int page, double width, double height) {}
  public record PageBottomNumberCandidate(int page, String visibleText, double x, double y) {}

  public record KeywordDecision(KeywordStatus status, Integer page, Double firstKeywordX,
      Double continuationX, String unknownReason) {}

  /** No original text or PDF text is serialized in this provenance. */
  public record Provenance(String status, String sourceSha256, String pdfSha256,
      String renderer, String rendererVersion, String renderedAt, String reasonCode, int pageCount) {}

  public record Glyph(String text, double x, double width) {
    public Glyph { Objects.requireNonNull(text, "text"); }

    public Glyph(String text, double x) { this(text, x, 0); }
  }

  public record TextLine(int page, double y, List<Glyph> glyphs) {
    public TextLine { glyphs = List.copyOf(glyphs); }

    String text() {
      var text = new StringBuilder();
      for (Glyph glyph : glyphs) text.append(glyph.text());
      return text.toString();
    }

    Double firstVisibleX() {
      for (Glyph glyph : glyphs) if (!normalized(glyph.text()).isEmpty())
        return Double.isFinite(glyph.x()) ? glyph.x() : null;
      return null;
    }

  }
}
