package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Recognizes a heading suffix only when contents, sentence and formatting evidence agree. */
final class EmbeddedHeadingDetector {
  private static final Pattern SUFFIX = Pattern.compile(
      "(?<=[。！？!?；;])[\\t\\p{Zs}]*((\\d+(?:\\.\\d+){1,2})[\\t\\p{Zs}]*[^\\r\\n。！？!?；;]+?)[\\t\\p{Zs}]*$");
  private final Set<String> contentsTitles;

  EmbeddedHeadingDetector(ParsedDocument document) {
    var regions = new DocumentRegionClassifier();
    var titles = new HashSet<String>();
    for (var paragraph : document.paragraphs()) {
      if (paragraph.insideTextBox() || regions.isInsideTable(paragraph, document.tables())
          || !regions.hasDefiniteContentsEvidence(paragraph)) continue;
      titles.add(DocumentRegionClassifier.compactContentsText(
          DocumentRegionClassifier.contentsTitle(ManualLineBreakSupport.text(paragraph))));
    }
    contentsTitles = Set.copyOf(titles);
  }

  Optional<Match> find(ParagraphModel paragraph) {
    String raw = ManualLineBreakSupport.text(paragraph);
    var matcher = SUFFIX.matcher(raw);
    if (!matcher.find() || raw.substring(0, matcher.start(1) - 1).isBlank()) return Optional.empty();
    String title = matcher.group(1);
    if (!contentsTitles.contains(DocumentRegionClassifier.compactContentsText(title))) return Optional.empty();
    int level = matcher.group(2).chars().filter(c -> c == '.').count() == 1 ? 2 : 3;
    int start = matcher.start(1);
    int end = matcher.end(1);
    if (!hasHeadingFormatting(paragraph, start, end, level)) return Optional.empty();
    SourceAddress source = paragraph.source();
    return Optional.of(new Match(start, end, level, title,
        new SourceAddress(source.partName(), source.elementPath(), source.start() + start, source.start() + end)));
  }

  private static boolean hasHeadingFormatting(ParagraphModel paragraph, int start, int end, int level) {
    int offset = 0;
    boolean hasHan = false;
    for (var run : paragraph.runs()) {
      String text = run.text() == null ? "" : run.text();
      int runEnd = offset + text.length();
      if (runEnd > start && offset < end) {
        String overlap = text.substring(Math.max(0, start - offset), Math.min(text.length(), end - offset));
        if (!overlap.isBlank() && !Integer.valueOf(level == 2 ? 28 : 24).equals(run.fontSizeHalfPoints()))
          return false;
        if (overlap.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
          hasHan = true;
          if (!ParagraphFormatChecker.matchesChineseFont("黑体", run.eastAsiaFont())) return false;
        }
      }
      offset = runEnd;
    }
    return hasHan;
  }

  record Match(int start, int end, int level, String title, SourceAddress source) { }
}
