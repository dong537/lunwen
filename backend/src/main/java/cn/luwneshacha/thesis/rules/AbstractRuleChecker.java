package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import cn.luwneshacha.thesis.rendering.RenderedLayoutEvidence;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
  private final RenderedLayoutEvidence renderedLayout;

  AbstractRuleChecker(ParagraphFormatChecker formatChecker) {
    this(formatChecker, null);
  }

  AbstractRuleChecker(ParagraphFormatChecker formatChecker, RenderedLayoutEvidence renderedLayout) {
    this.formatChecker = formatChecker;
    this.headingChecker = new HeadingRuleChecker(formatChecker);
    this.renderedLayout = renderedLayout;
  }

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    return evaluate(document, rules).results();
  }

  @Override
  public Evaluation evaluate(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    var evidence = new Evidence();
    String language = null;
    boolean needTitleBlank = false;
    boolean previousWasBlank = false;
    int titleBlankLineCount = 0;
    boolean titleBlankCountReported = false;
    boolean countingTitleBlankLines = false;
    String pendingTitleBlankRule = null;
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
        evidence.completed("ZH-01-SPACING");
        pendingTitleBlankRule = "ZH-02-BLANK";
        evidence.unknown(pendingTitleBlankRule);
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
        pendingTitleBlankRule = "EN-01-BLANK";
        evidence.unknown(pendingTitleBlankRule);
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
          if (pendingTitleBlankRule != null) {
            evidence.resolveUnknown(pendingTitleBlankRule);
            pendingTitleBlankRule = null;
          }
        }
        if (needTitleBlank) needTitleBlank = false;
        previousWasBlank = true;
        continue;
      }
      if (pendingTitleBlankRule != null) {
        evidence.resolveUnknown(pendingTitleBlankRule);
        pendingTitleBlankRule = null;
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
      // A misspelled Chinese label is still an identifiable keyword paragraph.
      // Visit its format, bold, alignment and blank-line candidates; the format
      // check below rejects the label instead of silently dropping all owners.
      boolean keywords = normalized.matches("(?i)^(关键词|关键字|keywords)[:：].*");
      if (keywords) evidence.completed("zh".equals(language) ? "ZH-04-BLANK" : "EN-03-BLANK");
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
        assess(results, evidence, formatChecker.checkFont(run, rules, "ABSTRACT-FONT"));
        assess(results, evidence, formatChecker.checkSize(run, rules, "ABSTRACT-SIZE"));
      }
      assess(results, evidence, formatChecker.checkIndent(paragraph, rules, "ABSTRACT-INDENT"));
      assess(results, evidence, formatChecker.checkLineSpacing("ABSTRACT-LINE", paragraph));
      if (keywords) {
        if ("zh".equals(language)) {
          assess(results, evidence, checkKeywordAlignment(paragraph));
          assess(results, evidence, checkChineseKeywordLabelBold(paragraph));
          assess(results, evidence, checkChineseKeywordFormat(paragraph, text));
        } else {
          assess(results, evidence, checkEnglishKeywordLabelBold(paragraph));
          assess(results, evidence, checkEnglishKeywordFormat(paragraph, text));
          assess(results, evidence, englishKeywordAlignment(document, paragraph, text));
        }
        language = null;
      }
      abstractBlankLinesBeforeParagraph = ManualLineBreakSupport.blankLinesAfter(paragraph);
      previousWasBlank = abstractBlankLinesBeforeParagraph > 0;
    }
    return new Evaluation(List.copyOf(results), evidence.ruleRuns());
  }

  private static void assess(List<CheckResult> results, Evidence evidence, CheckResult decision) {
    // This is called at the candidate visit, including PASS results. The
    // report's final findings list is never used to infer candidate counts.
    evidence.assessed(decision);
    results.add(decision);
  }

  private static final class Evidence {
    private final Map<String, int[]> counts = new LinkedHashMap<>();

    private void completed(String ruleId) {
      counts.computeIfAbsent(ruleId, ignored -> new int[2])[0]++;
    }

    private void assessed(CheckResult decision) {
      int[] count = counts.computeIfAbsent(decision.ruleId(), ignored -> new int[2]);
      count[0]++;
      if (decision.judgement() == Judgement.REVIEW
          || decision.applicability() == Applicability.UNKNOWN
          || decision.executionStatus() != ExecutionStatus.EXECUTED) count[1]++;
    }

    private void unknown(String ruleId) {
      int[] count = counts.computeIfAbsent(ruleId, ignored -> new int[2]);
      count[0]++;
      count[1]++;
    }

    private void resolveUnknown(String ruleId) {
      int[] count = counts.get(ruleId);
      if (count == null || count[1] == 0)
        throw new IllegalStateException("no unresolved abstract candidate for " + ruleId);
      count[1]--;
    }

    private List<RuleRun> ruleRuns() {
      var runs = new ArrayList<RuleRun>();
      for (var entry : counts.entrySet()) {
        int candidates = entry.getValue()[0];
        int unknown = entry.getValue()[1];
        runs.add(new RuleRun(entry.getKey(), "AbstractRuleChecker", candidates,
            candidates - unknown, unknown, null));
      }
      return List.copyOf(runs);
    }
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
    if (normalized.startsWith("关键字"))
      return result("ABSTRACT-KEYWORD-FORMAT", "关键词：词1；词2；词3", text,
          "KEYWORD_LABEL_MISMATCH", Judgement.FAIL, paragraph.source());
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

  /** Paragraph indents do not prove the final horizontal position of rendered text. */
  private CheckResult checkEnglishKeywordWrapAlignment(ParagraphModel paragraph) {
    return result("EN-03-ALIGNMENT", "换行后与第一个关键词首字母对齐",
        "当前无法确认续行是否与第一个关键词首字母对齐，请在 Word 中检查；如未对齐，请调整段落格式",
        "ENGLISH_KEYWORD_WRAP_ALIGNMENT_UNKNOWN", Judgement.REVIEW, paragraph.source());
  }

  private CheckResult englishKeywordAlignment(ParsedDocument document, ParagraphModel paragraph, String text) {
    if (renderedLayout != null && renderedLayout.matchesSource(document.sourceSha256())) {
      var layout = renderedLayout.englishKeywordAlignment(text);
      if (layout.status() == RenderedLayoutEvidence.KeywordStatus.NO_WRAP)
        return result("EN-03-ALIGNMENT", "换行后与第一个关键词首字母对齐",
            "版面中的英文关键词没有换行", "", Judgement.PASS, paragraph.source());
      if (layout.status() == RenderedLayoutEvidence.KeywordStatus.ALIGNED)
        return result("EN-03-ALIGNMENT", "换行后与第一个关键词首字母对齐",
            "第 " + layout.page() + " 页的续行已对齐", "", Judgement.PASS, paragraph.source());
      if (layout.status() == RenderedLayoutEvidence.KeywordStatus.MISALIGNED)
        return result("EN-03-ALIGNMENT", "换行后与第一个关键词首字母对齐",
            "第 " + layout.page() + " 页的续行没有与第一个关键词首字母对齐",
            "ENGLISH_KEYWORD_WRAP_ALIGNMENT_MISMATCH", Judgement.FAIL, paragraph.source());
    }
    if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0)
      return checkEnglishKeywordWrapAlignment(paragraph);
    return result("EN-03-ALIGNMENT", "换行后与第一个关键词首字母对齐",
        "当前无法确认页面是否自动换行，请在 Word 中检查续行是否与第一个关键词对齐；如未对齐，请调整段落格式",
        "ENGLISH_KEYWORD_AUTO_WRAP_UNKNOWN", Judgement.REVIEW, paragraph.source());
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
