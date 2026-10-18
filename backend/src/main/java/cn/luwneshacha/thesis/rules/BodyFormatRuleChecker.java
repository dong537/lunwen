package cn.luwneshacha.thesis.rules;

import cn.luwneshacha.thesis.document.ParagraphModel;
import cn.luwneshacha.thesis.document.ParsedDocument;
import cn.luwneshacha.thesis.document.RunModel;
import cn.luwneshacha.thesis.document.SourceAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Applies the common body paragraph contract only inside the detected thesis body. */
final class BodyFormatRuleChecker implements RuleModule {
  private static final Pattern CITATION_MARKER = Pattern.compile(
      "\\[\\s*\\d+(?:\\s*[,\\uFF0C\\u3001;\\uFF1B\\-\\u2013\\u2014~\\uFF5E]\\s*\\d+)*\\s*\\]");
  private static final Pattern EQUATION = Pattern.compile("^\\(?((?:\\d+[\\-－]\\d+)|(?:[A-Z]\\d+))\\)?$");
  private static final Pattern CONTINUATION_MARKER = Pattern.compile("^续表(?:\\d+-\\d+|[A-Z]\\d+)$");
  private static final Pattern TOC_ENTRY = Pattern.compile(".+(?:\\.{2,}|……+|\\t|[\\p{Zs}]{2,})\\s*\\d+$");

  private final ParagraphFormatChecker formatChecker;
  private final HeadingRuleChecker headingRuleChecker;
  private final DocumentRegionClassifier regions = new DocumentRegionClassifier();

  BodyFormatRuleChecker(ParagraphFormatChecker formatChecker) {
    this.formatChecker = formatChecker;
    this.headingRuleChecker = new HeadingRuleChecker(formatChecker);
  }

  @Override
  public List<CheckResult> check(ParsedDocument document, RuleSet rules) {
    var results = new ArrayList<CheckResult>();
    Set<SourceAddress> captionLocations = captionLocations(document);
    boolean inMainBody = false;
    boolean inContents = false;
    boolean bodyStartFound = false;
    boolean bodyParagraphChecked = false;
    for (ParagraphModel paragraph : document.paragraphs()) {
      if (paragraph.insideTextBox() || regions.hasDefiniteContentsEvidence(paragraph)) continue;
      String text = text(paragraph);
      String normalized = compact(text);
      boolean insideTable = regions.isInsideTable(paragraph, document.tables());
      if (isVisualBlank(paragraph)) continue;
      if (!insideTable && (normalized.equals("目录") || normalized.equalsIgnoreCase("CONTENTS")))
        inContents = true;
      boolean tocEntry = inContents && isTocEntry(text);
      if (!insideTable && !tocEntry && regions.isBodyChapterStart(paragraph, document)) {
        inMainBody = true;
        bodyStartFound = true;
      }
      if (tocEntry) continue;
      if (!insideTable && inContents && (regions.isBodyChapterStart(paragraph, document)
          || regions.isEndMatterHeading(paragraph, document))) inContents = false;
      if (!insideTable && inMainBody && regions.isEndMatterHeading(paragraph, document)) inMainBody = false;
      if (!inMainBody || insideTable
          || captionLocations.contains(paragraphLocation(paragraph.source()))
          || hasCaptionStyle(paragraph)
          || !isBodyParagraph(paragraph)) continue;
      bodyParagraphChecked = true;
      results.add(formatChecker.checkLineSpacing("BODY-LINE", paragraph));
      results.add(formatChecker.checkIndent(paragraph, rules, "BODY-INDENT"));
      for (RunModel run : bodyTextRuns(paragraph)) {
        results.add(formatChecker.checkFont(run, rules, "BODY-FONT"));
        results.add(formatChecker.checkSize(run, rules, "BODY-SIZE"));
      }
    }
    if (hasFrontMatterSignal(document) && !bodyStartFound) {
      results.add(bodyScopeReview(document, "正文起点未能从章节证据确定"));
    } else if (hasFrontMatterSignal(document) && bodyStartFound && !bodyParagraphChecked) {
      results.add(bodyScopeReview(document, "已找到正文起点，但没有可检查的正文段落"));
    }
    return List.copyOf(results);
  }

  private CheckResult bodyScopeReview(ParsedDocument document, String actual) {
    SourceAddress anchor = document.paragraphs().stream()
        .filter(paragraph -> !paragraph.insideTextBox())
        .filter(paragraph -> !regions.isInsideTable(paragraph, document.tables()))
        .filter(paragraph -> !text(paragraph).isBlank())
        .map(ParagraphModel::source).findFirst()
        .orElse(new SourceAddress("word/document.xml", List.of(), 0, 0));
    return new CheckResult("BODY-SCOPE", Applicability.APPLICABLE, ExecutionStatus.EXECUTED,
        Judgement.REVIEW, "正文区域可由章节证据确定", actual, "BODY_REGION_UNDETERMINED", anchor);
  }

  private static List<RunModel> bodyTextRuns(ParagraphModel paragraph) {
    String text = paragraph.runs().stream().map(run -> run.text() == null ? "" : run.text())
        .reduce("", String::concat);
    Matcher matcher = CITATION_MARKER.matcher(text);
    var citationRanges = new ArrayList<int[]>();
    while (matcher.find()) citationRanges.add(new int[] {matcher.start(), matcher.end()});
    if (citationRanges.isEmpty()) return paragraph.runs();

    var bodyRuns = new ArrayList<RunModel>();
    int runStart = 0;
    for (RunModel run : paragraph.runs()) {
      String runText = run.text() == null ? "" : run.text();
      int runEnd = runStart + runText.length();
      int cursor = runStart;
      for (int[] range : citationRanges) {
        if (range[1] <= runStart) continue;
        if (range[0] >= runEnd) break;
        int bodyEnd = Math.min(range[0], runEnd);
        if (cursor < bodyEnd) bodyRuns.add(slice(run, cursor - runStart, bodyEnd - runStart));
        cursor = Math.max(cursor, Math.min(range[1], runEnd));
      }
      if (cursor < runEnd) bodyRuns.add(slice(run, cursor - runStart, runText.length()));
      runStart = runEnd;
    }
    return List.copyOf(bodyRuns);
  }

  private static RunModel slice(RunModel run, int start, int end) {
    SourceAddress source = run.source();
    SourceAddress sliceSource = source == null ? null : new SourceAddress(source.partName(),
        source.elementPath(), source.start() + start, source.start() + end);
    return new RunModel(run.text().substring(start, end), run.asciiFont(), run.highAnsiFont(),
        run.eastAsiaFont(), run.complexFont(), run.fontSizeHalfPoints(),
        run.complexFontSizeHalfPoints(), run.bold(), run.verticalAlignment(), sliceSource);
  }

  private boolean hasFrontMatterSignal(ParsedDocument document) {
    return document.paragraphs().stream()
        .filter(paragraph -> !paragraph.insideTextBox())
        .filter(paragraph -> !regions.isInsideTable(paragraph, document.tables()))
        .map(BodyFormatRuleChecker::text)
        .map(BodyFormatRuleChecker::compact)
        .anyMatch(value -> value.equals("摘要") || value.equalsIgnoreCase("ABSTRACT")
            || value.equals("目录") || value.equalsIgnoreCase("CONTENTS")
            || value.equals("参考文献") || value.equalsIgnoreCase("REFERENCES")
            || value.equals("致谢") || value.equalsIgnoreCase("ACKNOWLEDGMENTS")
            || value.equalsIgnoreCase("ACKNOWLEDGEMENTS") || value.equals("附录")
            || value.equalsIgnoreCase("APPENDIX")
            || value.equals("本科生毕业设计（论文）") || value.equals("本科生毕业设计(论文)"));
  }

  private static Set<SourceAddress> captionLocations(ParsedDocument document) {
    var locations = new HashSet<SourceAddress>();
    for (var image : document.images())
      if (image.captionSource() != null) locations.add(paragraphLocation(image.captionSource()));
    for (var table : document.tables())
      if (table.captionSource() != null) locations.add(paragraphLocation(table.captionSource()));
    return locations;
  }

  private static SourceAddress paragraphLocation(SourceAddress source) {
    // Object bindings identify the paragraph; their caption character ranges can differ.
    return new SourceAddress(source.partName(), source.elementPath(), 0, 0);
  }

  private static boolean hasCaptionStyle(ParagraphModel paragraph) {
    return isCaptionStyle(paragraph.paragraphStyleName())
        || isCaptionStyle(paragraph.paragraphStyleId());
  }

  private static boolean isCaptionStyle(String style) {
    return "Caption".equalsIgnoreCase(style) || "题注".equals(style);
  }

  private boolean isBodyParagraph(ParagraphModel paragraph) {
    String text = text(paragraph).strip();
    if (text.isBlank() || isSummaryTitle(text) || CONTINUATION_MARKER.matcher(text).matches()
        || EQUATION.matcher(text).matches() || TOC_ENTRY.matcher(text).matches()
        || regions.isBodyChapterStart(paragraph) || headingRuleChecker.isLowerHeading(text)) return false;
    return headingRuleChecker.headingLevel(paragraph) == 0 && !isNamedSectionHeading(text);
  }

  private static boolean isNamedSectionHeading(String text) {
    return text.matches("^(摘要|Abstract|关键词|Keywords|目录|Contents|参考文献|References|致谢|Acknowledg(?:e)?ments)$")
        || "结论与展望".equals(text);
  }

  private static boolean isTocEntry(String text) {
    return TOC_ENTRY.matcher(text).matches()
        || text.matches("^第[一二三四五六七八九十百千万零〇]+章\\s+.+\\d$")
        || text.matches("^\\d+(?:\\.\\d+){1,2}\\s+.+\\d$");
  }

  private static boolean isSummaryTitle(String text) {
    return compact(text).equals("总结");
  }

  private static boolean isVisualBlank(ParagraphModel paragraph) {
    return text(paragraph).codePoints().allMatch(codePoint -> Character.isWhitespace(codePoint)
        || Character.isSpaceChar(codePoint) || codePoint == 0x200B || codePoint == 0x200C
        || codePoint == 0x200D || codePoint == 0x2060 || codePoint == 0xFEFF);
  }

  private static String text(ParagraphModel paragraph) {
    return paragraph.runs().stream().map(RunModel::text).reduce("", String::concat).strip();
  }

  private static String compact(String text) {
    return text.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)
        && !Character.isSpaceChar(codePoint) && codePoint != 0x200B && codePoint != 0x200C
        && codePoint != 0x200D && codePoint != 0x2060 && codePoint != 0xFEFF)
        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
  }
}
