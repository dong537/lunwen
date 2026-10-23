package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the Chinese and English abstract blocks as one semantic region.
 *
 * <p>The module owns abstract state (language, title gap and keyword gap),
 * while all font, size, indent and line spacing comparisons go through the
 * shared {@link ParagraphFormatChecker}. This keeps abstract rules isolated
 * from the main body state machine and makes the applicability boundary
 * explicit.</p>
 */
final class AbstractRuleChecker implements RuleModule {
  private static final Pattern CHAPTER = Pattern.compile("^第[一二三四五六七八九十百千零〇\\d]+章");
  private static final Pattern END_MATTER = Pattern.compile(
      "^(参考文献|References|致谢|Acknowledgments?|附录|Appendix)(?:[\\s\\p{Z}]|$).*$",
      Pattern.CASE_INSENSITIVE);

  private final ParagraphFormatChecker formatChecker;
  private final HeadingRuleChecker headingChecker;

  AbstractRuleChecker(ParagraphFormatChecker formatChecker) {
    this.formatChecker = formatChecker;
    this.headingChecker = new HeadingRuleChecker(formatChecker);
  }

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    String language = null;
    boolean needTitleBlank = false;
    boolean previousWasBlank = false;
    int titleBlankLineCount = 0;
    boolean titleBlankCountReported = false;
    boolean countingTitleBlankLines = false;
    int abstractBlankLinesBeforeParagraph = 0;
    boolean bodyStarted = false;
    DocumentRegionClassifier regions = new DocumentRegionClassifier();
    for (ParagraphModel paragraph : document.paragraphs()) {
      if (bodyStarted) continue;
      if (paragraph.insideTextBox()) continue;
      if (regions.isInsideTable(paragraph, document.tables())) continue;
      String text = paragraphText(paragraph);
      String normalized = compact(text);
      boolean abstractTitle = normalized.equals("摘要") || normalized.equalsIgnoreCase("ABSTRACT");
      if (regions.isBodyChapterStart(paragraph, document)
          || (language != null && !abstractTitle && headingChecker.headingLevel(paragraph) == 1)) {
        language = null;
        bodyStarted = true;
        continue;
      }
      if (normalized.equals("摘要")) {
        language = "zh";
        titleBlankLineCount = ManualLineBreakSupport.blankLinesAfter(paragraph);
        needTitleBlank = titleBlankLineCount == 0;
        previousWasBlank = false;
        titleBlankCountReported = false;
        countingTitleBlankLines = true;
        abstractBlankLinesBeforeParagraph = 0;
        if (!ManualLineBreakSupport.stripBoundaryBreaks(text).matches("^摘 {4}要$")) {
          results.add(result("ZH-01-SPACING", "摘    要（4 个普通空格）", text,
              "ABSTRACT_TITLE_SPACING_MISMATCH", Judgement.FAIL, paragraph.source()));
        }
        continue;
      }
      if (normalized.equalsIgnoreCase("ABSTRACT")) {
        language = "en";
        titleBlankLineCount = ManualLineBreakSupport.blankLinesAfter(paragraph);
        needTitleBlank = titleBlankLineCount == 0;
        previousWasBlank = false;
        titleBlankCountReported = false;
        countingTitleBlankLines = true;
        abstractBlankLinesBeforeParagraph = 0;
        continue;
      }
      if (language == null) continue;
      if (isEndMatterHeading(text) || isChapterHeading(text)
          || normalized.equals("目录") || normalized.equalsIgnoreCase("CONTENTS")) {
        language = null;
        continue;
      }
      if (isVisualBlankParagraph(paragraph)) {
        int blankLines = ManualLineBreakSupport.visualBlankLines(paragraph);
        abstractBlankLinesBeforeParagraph += blankLines;
        if (countingTitleBlankLines) titleBlankLineCount += blankLines;
        if (countingTitleBlankLines && !needTitleBlank && titleBlankLineCount > 1
            && !titleBlankCountReported) {
          results.add(result("zh".equals(language) ? "ZH-02-BLANK" : "EN-01-BLANK", "空一行",
              String.valueOf(titleBlankLineCount), "ABSTRACT_TITLE_BLANK_LINE_COUNT_MISMATCH",
              Judgement.FAIL, paragraph.source()));
          titleBlankCountReported = true;
        }
        if (needTitleBlank) needTitleBlank = false;
        previousWasBlank = true;
        continue;
      }
      if (needTitleBlank) {
        results.add(result("zh".equals(language) ? "ZH-02-BLANK" : "EN-01-BLANK", "空一行",
            "未空一行", "ABSTRACT_TITLE_BLANK_LINE_MISSING", Judgement.FAIL, paragraph.source()));
        needTitleBlank = false;
      }
      if (countingTitleBlankLines && titleBlankLineCount > 1 && !titleBlankCountReported) {
        results.add(result("zh".equals(language) ? "ZH-02-BLANK" : "EN-01-BLANK", "空一行",
            String.valueOf(titleBlankLineCount), "ABSTRACT_TITLE_BLANK_LINE_COUNT_MISMATCH",
            Judgement.FAIL, paragraph.source()));
        titleBlankCountReported = true;
      }
      countingTitleBlankLines = false;
      boolean keywords = normalized.matches("(?i)^(关键词|keywords)[:：].*");
      if (keywords && abstractBlankLinesBeforeParagraph > 1) {
        results.add(result("zh".equals(language) ? "ZH-04-BLANK" : "EN-03-BLANK", "空一行",
            String.valueOf(abstractBlankLinesBeforeParagraph),
            "ABSTRACT_KEYWORD_BLANK_LINE_COUNT_MISMATCH", Judgement.FAIL, paragraph.source()));
      }
      if (keywords && !previousWasBlank) {
        results.add(result("zh".equals(language) ? "ZH-04-BLANK" : "EN-03-BLANK", "空一行",
            "未空一行", "ABSTRACT_KEYWORD_BLANK_LINE_MISSING", Judgement.FAIL, paragraph.source()));
      }
      for (RunModel run : paragraph.runs()) {
        if (ManualLineBreakSupport.isBreakOnly(run.text())) continue;
        results.add(formatChecker.checkFont(run, rules, "ABSTRACT-FONT"));
        results.add(formatChecker.checkSize(run, rules, "ABSTRACT-SIZE"));
      }
      results.add(formatChecker.checkIndent(paragraph, rules, "ABSTRACT-INDENT"));
      results.add(formatChecker.checkLineSpacing("ABSTRACT-LINE", paragraph));
      if (keywords) {
        if ("zh".equals(language)) {
          results.add(checkKeywordAlignment(paragraph));
          results.add(checkChineseKeywordLabelBold(paragraph));
          results.add(checkChineseKeywordFormat(paragraph, text));
        } else {
          results.add(checkEnglishKeywordLabelBold(paragraph));
          results.add(checkEnglishKeywordFormat(paragraph, text));
          if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            results.add(checkEnglishKeywordWrapAlignment(paragraph, text));
          }
        }
        language = null;
      }
      abstractBlankLinesBeforeParagraph = ManualLineBreakSupport.blankLinesAfter(paragraph);
      previousWasBlank = abstractBlankLinesBeforeParagraph > 0;
    }
    return List.copyOf(results);
  }

  private CheckResult checkChineseKeywordLabelBold(ParagraphModel paragraph) {
    return checkLabelBold(paragraph, "关键词", "ABSTRACT-KEYWORD-BOLD");
  }

  private CheckResult checkEnglishKeywordLabelBold(ParagraphModel paragraph) {
    String text = paragraphText(paragraph);
    int labelStart = leadingWhitespaceEnd(text);
    int labelEnd = labelStart + "Keywords".length();
    if (labelEnd > text.length() || !text.regionMatches(true, labelStart, "Keywords", 0, "Keywords".length()))
      return result("EN-03-BOLD", "Keywords 标签单独加粗", "unknown", "KEYWORD_LABEL_UNREADABLE",
          Judgement.REVIEW, paragraph.source());
    if (labelEnd < text.length() && (text.charAt(labelEnd) == ':' || text.charAt(labelEnd) == '：'))
      labelEnd++;

    int offset = 0;
    for (RunModel run : paragraph.runs()) {
      int end = offset + run.text().length();
      boolean overlapsLabel = offset < labelEnd && end > labelStart;
      if (overlapsLabel && run.bold() == null)
        return result("EN-03-BOLD", "Keywords 标签单独加粗", "unknown", "BOLD_UNKNOWN",
            Judgement.REVIEW, paragraphRange(paragraph, Math.max(offset, labelStart), Math.min(end, labelEnd)));
      if (overlapsLabel && !run.bold())
        return result("EN-03-BOLD", "Keywords 标签单独加粗", "标签未加粗",
            "KEYWORD_LABEL_NOT_BOLD", Judgement.FAIL,
            paragraphRange(paragraph, Math.max(offset, labelStart), Math.min(end, labelEnd)));
      if (end > labelEnd) {
        int suffixOffset = Math.max(0, labelEnd - offset);
        String suffix = run.text().substring(suffixOffset);
        int contentOffset = firstNonWhitespaceOffset(suffix);
        if (contentOffset < suffix.length() && run.bold() == null)
          return result("EN-03-BOLD", "Keywords 标签单独加粗", "内容字重无法确认",
              "KEYWORD_CONTENT_BOLD_UNKNOWN", Judgement.REVIEW,
              paragraphRange(paragraph, offset + suffixOffset + contentOffset, end));
        if (contentOffset < suffix.length() && run.bold())
          return result("EN-03-BOLD", "Keywords 标签单独加粗", "关键词内容被加粗",
              "KEYWORD_CONTENT_BOLD", Judgement.FAIL,
              paragraphRange(paragraph, offset + suffixOffset + contentOffset, end));
      }
      offset = end;
    }
    if (offset < labelEnd)
      return result("EN-03-BOLD", "Keywords 标签单独加粗", "unknown", "KEYWORD_LABEL_UNREADABLE",
          Judgement.REVIEW, paragraph.source());
    return result("EN-03-BOLD", "Keywords 标签单独加粗", "仅标签加粗", "", Judgement.PASS,
        paragraph.source());
  }

  private static int firstNonWhitespaceOffset(String text) {
    int index = 0;
    while (index < text.length()) {
      int codePoint = text.codePointAt(index);
      if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) break;
      index += Character.charCount(codePoint);
    }
    return index;
  }

  private static SourceAddress paragraphRange(ParagraphModel paragraph, int start, int end) {
    SourceAddress source = paragraph.source();
    return new SourceAddress(source.partName(), source.elementPath(), source.start() + start,
        source.start() + end);
  }

  private static int leadingWhitespaceEnd(String text) {
    int offset = 0;
    while (offset < text.length()) {
      int codePoint = text.codePointAt(offset);
      if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) break;
      offset += Character.charCount(codePoint);
    }
    return offset;
  }

  private CheckResult checkLabelBold(ParagraphModel paragraph, String label, String ruleId) {
    int labelStart = leadingWhitespaceEnd(paragraphText(paragraph));
    int labelEnd = labelStart + label.length();
    int offset = 0;
    for (RunModel run : paragraph.runs()) {
      int end = offset + run.text().length();
      boolean overlapsLabel = offset < labelEnd && end > labelStart;
      if (overlapsLabel && run.bold() == null)
        return result(ruleId, "true", "unknown", "BOLD_UNKNOWN", Judgement.REVIEW,
            paragraphRange(paragraph, Math.max(offset, labelStart), Math.min(end, labelEnd)));
      if (overlapsLabel && !run.bold())
        return result(ruleId, "true", "false", "KEYWORD_LABEL_NOT_BOLD", Judgement.FAIL,
            paragraphRange(paragraph, Math.max(offset, labelStart), Math.min(end, labelEnd)));
      offset = end;
    }
    if (offset < labelEnd)
      return result(ruleId, "true", "unknown", "KEYWORD_LABEL_UNREADABLE", Judgement.REVIEW,
          paragraph.source());
    return result(ruleId, "true", "true", "", Judgement.PASS, paragraph.source());
  }

  private CheckResult checkKeywordAlignment(ParagraphModel paragraph) {
    if (paragraph.alignment() == null)
      return result("ABSTRACT-KEYWORD-ALIGN", "both", "unknown", "KEYWORD_ALIGNMENT_UNKNOWN",
          Judgement.REVIEW, paragraph.source());
    boolean matches = "both".equalsIgnoreCase(paragraph.alignment())
        || "justify".equalsIgnoreCase(paragraph.alignment());
    return result("ABSTRACT-KEYWORD-ALIGN", "both", paragraph.alignment(),
        matches ? "" : "KEYWORD_ALIGNMENT_MISMATCH", matches ? Judgement.PASS : Judgement.FAIL,
        paragraph.source());
  }

  private CheckResult checkChineseKeywordFormat(ParagraphModel paragraph, String text) {
    String normalized = text.strip();
    if (!normalized.startsWith("关键词："))
      return result("ABSTRACT-KEYWORD-FORMAT", "关键词：词1；词2；词3", text,
          "KEYWORD_SEPARATOR_MISMATCH", Judgement.FAIL, paragraph.source());
    String list = normalized.substring("关键词：".length());
    if (list.indexOf(';') >= 0)
      return result("ABSTRACT-KEYWORD-FORMAT", "3-5 个关键词，用中文分号分隔且末尾无标点", text,
          "KEYWORD_LIST_MISMATCH", Judgement.FAIL, paragraph.source());
    String[] words = list.split("；", -1);
    boolean trailingPunctuation = list.matches(".*[。！？；，,.!?]$");
    boolean valid = words.length >= 3 && words.length <= 5
        && Arrays.stream(words).allMatch(word -> !word.isBlank()) && !trailingPunctuation;
    return result("ABSTRACT-KEYWORD-FORMAT", "3-5 个关键词，用中文分号分隔且末尾无标点", text,
        valid ? "" : "KEYWORD_LIST_MISMATCH", valid ? Judgement.PASS : Judgement.FAIL,
        paragraph.source());
  }

  private CheckResult checkEnglishKeywordFormat(ParagraphModel paragraph, String text) {
    String normalized = text.replaceAll("\\s+", " ").strip();
    Matcher matcher = Pattern.compile("(?i)^Keywords\\s*[:：]\\s*(.+)$").matcher(normalized);
    if (!matcher.matches())
      return result("EN-03-FORMAT", "Keywords: word1; word2; word3", text,
          "ENGLISH_KEYWORD_SEPARATOR_MISMATCH", Judgement.FAIL, paragraph.source());
    String list = matcher.group(1);
    boolean asciiSeparator = list.indexOf(';') >= 0;
    boolean chineseSeparator = list.indexOf('；') >= 0;
    if (asciiSeparator && chineseSeparator)
      return result("EN-03-FORMAT", "统一使用一种分号", text,
          "ENGLISH_KEYWORD_SEPARATOR_MISMATCH", Judgement.FAIL, paragraph.source());
    String[] keywords = list.split(asciiSeparator ? ";" : "；", -1);
    if (Arrays.stream(keywords).anyMatch(String::isBlank))
      return result("EN-03-FORMAT", "关键词之间用分号分隔", text,
          "ENGLISH_KEYWORD_SEPARATOR_MISMATCH", Judgement.FAIL, paragraph.source());
    return result("EN-03-FORMAT", "英文分号分隔关键词", text, "", Judgement.PASS, paragraph.source());
  }

  /**
   * A wrapped keyword line is aligned by a hanging paragraph indent in the
   * OOXML evidence.  Without both sides of that setting the final visual
   * column cannot be proved from the document tree, so the result is REVIEW.
   */
  private CheckResult checkEnglishKeywordWrapAlignment(ParagraphModel paragraph, String text) {
    boolean twipsEvidence = paragraph.leftIndentTwips() != null && paragraph.hangingIndentTwips() != null;
    boolean characterEvidence = paragraph.leftIndentChars() != null && paragraph.hangingIndentChars() != null;
    if ((!twipsEvidence && !characterEvidence) || paragraph.hangingEvidenceInvalid()
        || paragraph.leftEvidenceInvalid()) {
      return result("EN-03-ALIGNMENT", "换行后与第一个关键词首字母对齐", "XML 未提供悬挂缩进证据",
          "ENGLISH_KEYWORD_WRAP_ALIGNMENT_UNKNOWN", Judgement.REVIEW, paragraph.source());
    }
    boolean valid = twipsEvidence
        ? paragraph.leftIndentTwips() > 0 && paragraph.hangingIndentTwips() > 0
            && paragraph.leftIndentTwips() >= paragraph.hangingIndentTwips()
        : paragraph.leftIndentChars() > 0 && paragraph.hangingIndentChars() > 0
            && paragraph.leftIndentChars() >= paragraph.hangingIndentChars();
    if (!valid) {
      return result("EN-03-ALIGNMENT", "换行后与第一个关键词首字母对齐",
          "悬挂缩进值不能把续行移到第一个关键词起始位置",
          "ENGLISH_KEYWORD_WRAP_ALIGNMENT_MISMATCH", Judgement.FAIL, paragraph.source());
    }
    return result("EN-03-ALIGNMENT", "换行后与第一个关键词首字母对齐",
        "已读取左缩进和悬挂缩进", "", Judgement.PASS, paragraph.source());
  }

  private static String paragraphText(ParagraphModel paragraph) {
    return ManualLineBreakSupport.text(paragraph);
  }

  private static String compact(String text) {
    return text.codePoints()
        .filter(codePoint -> !Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)
            && codePoint != 0x200B && codePoint != 0x200C && codePoint != 0x200D
            && codePoint != 0x2060 && codePoint != 0xFEFF)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
        .toString();
  }

  private static boolean isVisualBlankParagraph(ParagraphModel paragraph) {
    return ManualLineBreakSupport.visualBlankLines(paragraph) > 0;
  }

  private static boolean isChapterHeading(String text) {
    String normalized = compact(text);
    return CHAPTER.matcher(normalized).find() && !text.matches(".*[；;。！？]$");
  }

  private static boolean isEndMatterHeading(String text) {
    String normalized = text.replaceAll("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]", "")
        .replaceAll("[\\s\\p{Z}]+", " ").strip();
    return END_MATTER.matcher(normalized).matches() || normalized.equals("参考文献")
        || normalized.equals("References") || normalized.equals("致谢")
        || normalized.equalsIgnoreCase("Acknowledgments")
        || normalized.equalsIgnoreCase("Acknowledgements");
  }

  private static CheckResult result(String ruleId, String expected, String actual, String reason,
      Judgement judgement, SourceAddress source) {
    return new CheckResult(ruleId, Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        judgement, expected, actual, reason, source);
  }
}
