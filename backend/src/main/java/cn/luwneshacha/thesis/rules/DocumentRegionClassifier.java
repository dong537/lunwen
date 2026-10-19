package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.document.TableModel;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared document-region evidence used by rules that cross paragraph/object boundaries. */
final class DocumentRegionClassifier {
  private static final Pattern TOC_ENTRY = Pattern.compile(".+(?:\\.{2,}|……+|\\t|[\\p{Zs}]{2,})\\s*\\d+$");
  private static final Pattern CONTENTS_STYLE = Pattern.compile("(?i)^(?:toc|目录)[\\s\\p{Zs}]*([1-3])$");
  static final Pattern NUMERIC_CONTENTS_WITH_INTERNAL_WHITESPACE = Pattern.compile(
      "^(\\d+(?:[\\p{javaWhitespace}\\p{Z}\\u200B-\\u200D\\u2060\\uFEFF]*\\."
          + "[\\p{javaWhitespace}\\p{Z}\\u200B-\\u200D\\u2060\\uFEFF]*\\d+){1,2})(.*)$",
      Pattern.DOTALL);
  private static final Pattern APPENDIX_HEADING = Pattern.compile(
      "^(?:附录|Appendix)[\\s\\p{Zs}]*([A-Z])(?:$|[\\s\\p{Zs}]|[:：]|\\p{IsHan}).*",
      Pattern.CASE_INSENSITIVE);
  private static final String AMBIGUOUS_PROSE_START = "的中内里为可需将与和是见";
  private static final Pattern TABLE_OR_FIGURE_CAPTION = Pattern.compile(
      "^(续表|表|图)\\s*[0-9一二三四五六七八九十]+", Pattern.CASE_INSENSITIVE);

  boolean hasDefiniteContentsEvidence(ParagraphModel paragraph) {
    return paragraph.generatedTocField() || contentsStyleLevel(paragraph) > 0;
  }

  int contentsStyleLevel(ParagraphModel paragraph) {
    for (String style : new String[] {paragraph.paragraphStyleId(), paragraph.paragraphStyleName()}) {
      if (style == null) continue;
      Matcher matcher = CONTENTS_STYLE.matcher(style);
      if (matcher.matches()) return Integer.parseInt(matcher.group(1));
    }
    return 0;
  }

  /** Weak layout evidence: callers must already be inside the contents region. */
  boolean looksLikeLowerLevelContentsEntry(ParagraphModel paragraph) {
    return NUMERIC_CONTENTS_WITH_INTERNAL_WHITESPACE.matcher(paragraphText(paragraph)).matches()
        && paragraph.runs().stream().filter(run -> !run.text().isBlank())
            .allMatch(run -> !Boolean.TRUE.equals(run.bold())
                && Integer.valueOf(24).equals(run.fontSizeHalfPoints()));
  }

  boolean isBeforeMainBody(ParsedDocument document, SourceAddress source) {
    SourceAddress firstChapter = null;
    for (ParagraphModel paragraph : document.paragraphs()) {
      if (paragraph.insideTextBox()) continue;
      if (isBodyChapterStart(paragraph, document)) {
        firstChapter = paragraph.source();
        break;
      }
    }
    return firstChapter != null && firstChapter.partName().equals(source.partName())
        && compareDocumentOrder(source.elementPath(), firstChapter.elementPath()) <= 0;
  }

  private int compareDocumentOrder(List<Integer> left, List<Integer> right) {
    int common = Math.min(left.size(), right.size());
    for (int index = 0; index < common; index++) {
      int compared = Integer.compare(left.get(index), right.get(index));
      if (compared != 0) return compared;
    }
    return Integer.compare(left.size(), right.size());
  }

  boolean isInsideTable(ParagraphModel paragraph, List<TableModel> tables) {
    List<Integer> paragraphPath = paragraph.source().elementPath();
    return tables.stream().anyMatch(table -> {
      SourceAddress tableSource = table.source();
      List<Integer> tablePath = tableSource.elementPath();
      return tableSource.partName().equals(paragraph.source().partName())
          && paragraphPath.size() > tablePath.size()
          && paragraphPath.subList(0, tablePath.size()).equals(tablePath);
    });
  }

  String appendixLetterAt(ParsedDocument document, SourceAddress source) {
    if (source == null) return null;
    List<ParagraphModel> paragraphs = document.paragraphs();
    int targetIndex = -1;
    List<Integer> sourcePath = source.elementPath();
    for (int index = 0; index < paragraphs.size(); index++) {
      List<Integer> paragraphPath = paragraphs.get(index).source().elementPath();
      if (paragraphPath.size() <= sourcePath.size()
          && sourcePath.subList(0, paragraphPath.size()).equals(paragraphPath)) {
        targetIndex = index;
        if (paragraphPath.equals(sourcePath)) break;
      }
    }
    if (targetIndex < 0) return null;
    String current = null;
    for (int index = 0; index <= targetIndex; index++) {
      if (paragraphs.get(index).insideTextBox()) continue;
      String heading = appendixHeadingLetter(paragraphs.get(index), document);
      if (heading != null) current = heading;
    }
    return current;
  }

  boolean isFigureReferencedInBody(ParsedDocument document, String targetNumber, SourceAddress captionSource,
      Pattern figureNumberPattern) {
    return isLabelReferencedInBody(document, targetNumber, captionSource, figureNumberPattern);
  }

  boolean hasUnnumberedFigureReference(ParsedDocument document, SourceAddress captionSource,
      Pattern figureNumberPattern, Pattern unnumberedReferencePattern) {
    boolean inMainBody = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = paragraphText(paragraph);
      if (isBodyChapterStart(paragraph, document)) inMainBody = true;
      if (inMainBody && isEndMatterHeading(paragraph, document)) inMainBody = false;
      if (!inMainBody || isInsideTable(paragraph, document.tables()) || (captionSource != null
          && paragraph.source().elementPath().equals(captionSource.elementPath()))) continue;
      if (unnumberedReferencePattern.matcher(text).find() && !figureNumberPattern.matcher(text).find()) return true;
    }
    return false;
  }

  boolean isLabelReferencedInBody(ParsedDocument document, String targetNumber, SourceAddress captionSource,
      Pattern labelPattern) {
    boolean inMainBody = false;
    String normalizedTarget = targetNumber.replace('－', '-');
    for (ParagraphModel paragraph : document.paragraphs()) {
      String text = paragraphText(paragraph);
      if (isBodyChapterStart(paragraph, document)) inMainBody = true;
      if (inMainBody && isEndMatterHeading(paragraph, document)) inMainBody = false;
      if (!inMainBody || (captionSource != null
          && paragraph.source().elementPath().equals(captionSource.elementPath()))
          || isInsideTable(paragraph, document.tables()) || TABLE_OR_FIGURE_CAPTION.matcher(text).find()) continue;
      Matcher reference = labelPattern.matcher(text);
      while (reference.find()) {
        String referencedNumber = reference.group(1).replaceAll("[\\s\\p{Zs}]+", "");
        if (normalizedTarget.equals(referencedNumber)) return true;
      }
    }
    return false;
  }

  static String paragraphText(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(run -> run.text() == null ? "" : run.text()).reduce("", String::concat).trim();
  }

  String appendixHeadingLetter(ParagraphModel paragraph) {
    if (paragraph.insideTextBox() || hasDefiniteContentsEvidence(paragraph)) return null;
    String text = paragraphText(paragraph).strip();
    if (TOC_ENTRY.matcher(text).matches()
        || (paragraph.outlineLevel() != null && paragraph.outlineLevel() != 0)
        || (paragraph.outlineLevel() == null && text.matches(".*\\s+\\d+$"))) return null;
    Matcher matcher = APPENDIX_HEADING.matcher(text);
    return matchesAppendixHeading(paragraph, text, matcher) ? matcher.group(1) : null;
  }

  String appendixHeadingLetter(ParagraphModel paragraph, ParsedDocument document) {
    return isInsideTable(paragraph, document.tables()) ? null : appendixHeadingLetter(paragraph);
  }

  private boolean matchesAppendixHeading(ParagraphModel paragraph, String text, Matcher matcher) {
    if (!matcher.matches()) return false;
    String suffix = text.substring(matcher.end(1)).stripLeading();
    if (suffix.isEmpty() || ":：".indexOf(suffix.charAt(0)) >= 0) return true;
    return Integer.valueOf(0).equals(paragraph.outlineLevel())
        || !AMBIGUOUS_PROSE_START.contains(suffix.substring(0, 1));
  }

  boolean isBodyChapterStart(ParagraphModel paragraph) {
    if (paragraph.insideTextBox() || hasDefiniteContentsEvidence(paragraph)
        || isEndMatterHeading(paragraph)) return false;
    return isNumberedChapterHeading(paragraph) || isAutomaticNumberedChapter(paragraph)
        || isUnnumberedIntroductionHeading(paragraphText(paragraph));
  }

  boolean isBodyChapterStart(ParagraphModel paragraph, ParsedDocument document) {
    return !isInsideTable(paragraph, document.tables()) && isBodyChapterStart(paragraph);
  }

  boolean isNumberedChapterHeading(ParagraphModel paragraph) {
    if (hasDefiniteContentsEvidence(paragraph)) return false;
    String text = paragraphText(paragraph);
    return isChapterHeading(text);
  }

  boolean isAutomaticNumberedChapter(ParagraphModel paragraph) {
    return !hasDefiniteContentsEvidence(paragraph) && !isEndMatterHeading(paragraph)
        && paragraph.automaticNumberingActive()
        && Integer.valueOf(0).equals(paragraph.outlineLevel())
        && paragraph.numberingLabel() != null
        && paragraph.numberingLabel().matches("(?:[0-9]+|第[0-9]+章)");
  }

  private boolean isChapterHeading(String text) {
    String normalized = text == null ? "" : text.replaceAll("[\\s\\p{Zs}\\u200B\\u200C\\u200D\\u2060\\uFEFF]+", "");
    return normalized.matches("^第[0-9一二三四五六七八九十百千零〇]+章.+$")
        && (text == null || !text.matches(".*[；;。！？]$"))
        && !TOC_ENTRY.matcher(text).matches();
  }

  private boolean isUnnumberedIntroductionHeading(String text) {
    if (text == null) return false;
    String normalized = text.codePoints()
        .filter(codePoint -> !Character.isWhitespace(codePoint)
            && !Character.isSpaceChar(codePoint)
            && codePoint != 0x200B && codePoint != 0x200C && codePoint != 0x200D
            && codePoint != 0x2060 && codePoint != 0xFEFF)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
    return normalized.equals("绪论") || normalized.equals("引言");
  }

  boolean isEndMatterHeading(ParagraphModel paragraph) {
    if (paragraph.insideTextBox() || hasDefiniteContentsEvidence(paragraph)) return false;
    String text = paragraphText(paragraph);
    String normalized = text == null ? "" : text.codePoints()
        .filter(codePoint -> !Character.isWhitespace(codePoint)
            && !Character.isSpaceChar(codePoint)
            && codePoint != 0x200B && codePoint != 0x200C && codePoint != 0x200D
            && codePoint != 0x2060 && codePoint != 0xFEFF)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
    if (normalized.equals("参考文献") || normalized.equalsIgnoreCase("References") || normalized.equals("致谢")
        || normalized.equalsIgnoreCase("Acknowledgments") || normalized.equalsIgnoreCase("Acknowledgements")) return true;
    if (normalized.equals("附录")) return true;
    String headingText = text == null ? "" : text.replaceAll("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]", "")
        .replaceAll("[\\s\\p{Z}]+", " ").strip();
    Matcher appendix = APPENDIX_HEADING.matcher(headingText);
    return matchesAppendixHeading(paragraph, headingText, appendix)
        || headingText.matches("(?i)^Appendix(?:\\s+[A-Z](?:\\s+.+)?)?$");
  }

  boolean isEndMatterHeading(ParagraphModel paragraph, ParsedDocument document) {
    return !isInsideTable(paragraph, document.tables()) && isEndMatterHeading(paragraph);
  }
}
